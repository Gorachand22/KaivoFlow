package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TriggerReliabilityTest {
    private fun spark(id: String, type: String, params: Map<String, Any?> = emptyMap(), disabled: Boolean = false) =
        StepNode(id = id, type = type, name = id, params = params.toImmutableMap(), disabled = disabled)

    private fun match(step: StepNode, payload: Map<String, Any?>, kind: String = step.type) =
        EventTriggerMatcher.matches(step, kind, payload, "com.kaivoflow.app")

    @Test fun notificationRequiresAllConfiguredFilters() {
        val step = spark("n", TriggerSpecs.NOTIFICATION, mapOf("packageNames" to "com.bank, com.other", "titleContains" to "payment", "textContains" to "DEBIT"))
        val good = mapOf("app" to "com.bank", "title" to "Payment alert", "text" to "Card debit", "isOngoing" to false)
        assertTrue(match(step, good))
        assertFalse(match(step, good + ("app" to "com.wrong")))
        assertFalse(match(step, good + ("title" to "Welcome")))
        assertFalse(match(step, good + ("text" to "credited")))
        assertFalse(match(step, good + ("isOngoing" to true)))
    }

    @Test fun ownAndMissingPackageAreRejectedByDefault() {
        val step = spark("n", TriggerSpecs.NOTIFICATION)
        assertFalse(match(step, mapOf("app" to "com.kaivoflow.app")))
        assertFalse(match(step, emptyMap()))
        assertTrue(match(step, mapOf("app" to "com.example")))
        assertTrue(match(spark("n", step.type, mapOf("ignoreOwnApp" to false)), mapOf("app" to "com.kaivoflow.app")))
    }

    @Test fun regexFailsClosedAndIsReportedInValidation() {
        val step = spark("n", TriggerSpecs.NOTIFICATION, mapOf("textMatches" to ".*"))
        assertFalse(match(step, mapOf("app" to "com.example", "text" to "anything")))
        assertTrue(NodeCatalog.registry().validate(step).any { it.code == "regex_not_available" })
    }

    @Test fun smsAndShareAndUnknownEventsAreFiltered() {
        val sms = spark("s", TriggerSpecs.SMS, mapOf("senderContains" to "BANK", "bodyContains" to "paid"))
        assertTrue(match(sms, mapOf("from" to "bank-123", "body" to "PAID now")))
        assertFalse(match(sms, mapOf("from" to "other", "body" to "paid")))
        assertFalse(match(spark("s", TriggerSpecs.SMS), emptyMap()))
        assertFalse(match(sms.copy(disabled = true), mapOf("from" to "bank", "body" to "paid")))
        assertFalse(match(sms, mapOf("from" to "bank", "body" to "paid"), TriggerSpecs.SHARE))
        val share = spark("share", TriggerSpecs.SHARE)
        assertTrue(match(share, mapOf("text" to "hello")))
        assertFalse(match(share, emptyMap()))
        assertFalse(match(spark("b", TriggerSpecs.BATTERY_LOW), emptyMap()))
    }

    private fun runner() = FlowRunner(NodeCatalog.registry(), TestEvaluator(), RecordingHttp(), TestCredentials())
    private fun flow(vararg steps: StepNode, streams: List<StreamEdge>) = Flow(
        id = "f", name = "Flow", steps = persistentListOf(*steps), streams = persistentListOf(*streams.toTypedArray())
    )

    @Test fun manualAndScheduleTicksProvideOneItem() = runBlocking {
        for ((type, kind) in listOf(TriggerSpecs.MANUAL to "manual", TriggerSpecs.SCHEDULE to "scheduled")) {
            val f = flow(spark("t", type), spark("a", "data.set", mapOf("fields" to mapOf("value" to 1))),
                streams = listOf(StreamEdge("e", "t", "a")))
            val record = runner().run(f, RunTrigger(kind = kind))
            assertEquals(RunStatus.Succeeded, record.status)
            assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "a" }.status)
            assertEquals(1, record.stepRuns.first { it.stepId == "a" }.itemsIn)
        }
    }

    @Test fun onlySelectedSparkReceivesEvent() = runBlocking {
        val f = flow(spark("one", TriggerSpecs.NOTIFICATION), spark("two", TriggerSpecs.NOTIFICATION),
            spark("a", "data.set", mapOf("assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "first")))),
            spark("b", "data.set", mapOf("assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "second")))),
            streams = listOf(StreamEdge("e1", "one", "a"), StreamEdge("e2", "two", "b")))
        val record = runner().run(f, RunTrigger(kind = TriggerSpecs.NOTIFICATION,
            startAtStepId = "two", items = persistentListOf(FlowData(mapOf("text" to "event")))))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "one" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "a" }.status)
        assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "two" }.status)
        assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "b" }.status)
    }

    @Test fun invalidOrWrongSparkIdFailsWithoutRunningActions() = runBlocking {
        val f = flow(spark("t", TriggerSpecs.MANUAL), spark("a", "data.set", mapOf("assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "hello")))),
            streams = listOf(StreamEdge("e", "t", "a")))
        for (trigger in listOf(RunTrigger(kind = "manual", startAtStepId = "missing"),
            RunTrigger(kind = "scheduled", startAtStepId = "t"), RunTrigger(kind = "webhook"))) {
            val record = runner().run(f, trigger)
            assertEquals(RunStatus.Failed, record.status)
            assertTrue(record.stepRuns.isEmpty())
        }
    }
    @Test fun emptyWebhookPayloadDoesNotInventAnEventItem() = runBlocking {
        val f = flow(spark("t", TriggerSpecs.WEBHOOK, mapOf("path" to "test")),
            spark("a", "data.set", mapOf("assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "hello")))),
            streams = listOf(StreamEdge("e", "t", "a")))
        val record = runner().run(f, RunTrigger(kind = "webhook"))
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "a" }.status)
    }

}
