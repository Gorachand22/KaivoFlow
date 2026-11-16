package com.kaivoflow.core.data

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowGroup
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RetryPolicy
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StickyNote
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence is the highest-consequence logic in the app: a codec bug silently eats a user's Flows
 * on the next save. These tests pin the round-trip for every field the editor can produce, plus the
 * forward-compatibility rules (unknown fields ignored, bad JSON rejected without throwing).
 */
class JsonCodecTest {

    private fun sampleFlow(): Flow = Flow(
        id = "flow-1",
        name = "Morning Brief",
        steps = listOf(
            StepNode(
                id = "s1",
                type = "spark.schedule",
                name = "Every morning",
                params = persistentMapOf(
                    "frequency" to "daily",
                    "time" to "08:00",
                    "timezone" to "Asia/Kolkata",
                    "runOnlyOnWifi" to true,
                    "interval" to 15.0,
                    "nothing" to null,
                ),
                x = 24f,
                y = -8f,
                notes = "keep me",
                onError = OnErrorPolicy.Continue,
                retry = RetryPolicy(enabled = true, maxAttempts = 3, initialDelayMs = 500, backoffMultiplier = 2.0),
                timeoutMs = 30_000,
            ),
            StepNode(
                id = "s2",
                type = "net.http",
                name = "Fetch weather",
                params = persistentMapOf(
                    "method" to "GET",
                    "url" to "https://api.example.com/v1?city=Bhubaneswar",
                    "headers" to mapOf("X-Key" to "abc", "Accept" to "application/json"),
                    "body" to mapOf("nested" to listOf(1.0, 2.0, "three")),
                ),
                frozenOutput = persistentListOf(FlowData.of("temp" to 31.5, "sky" to "clear")),
                onError = OnErrorPolicy.Retry,
            ),
            StepNode(
                id = "s3",
                type = "device.notify",
                name = "Tell me",
                params = persistentMapOf("title" to "Weather", "body" to "{{ \$json.sky }}"),
                disabled = true,
            ),
        ).toImmutableList(),
        streams = listOf(
            StreamEdge(id = "e1", fromStepId = "s1", toStepId = "s2", fromPort = 0),
            StreamEdge(id = "e2", fromStepId = "s2", toStepId = "s3", fromPort = 1, label = "ok"),
        ).toImmutableList(),
        vars = persistentMapOf("city" to "Bhubaneswar", "threshold" to 30.0, "flag" to false),
        tags = persistentListOf("morning", "weather"),
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_500_000,
        isFavorite = true,
    )

    @Test
    fun `flow survives an encode decode round trip`() {
        val original = sampleFlow()
        val decoded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(original))
        assertNotNull("decode returned null — the codec dropped a field it writes", decoded)

        assertEquals(original.id, decoded!!.id)
        assertEquals(original.name, decoded.name)
        assertEquals(original.steps.size, decoded.steps.size)
        assertEquals(original.streams.size, decoded.streams.size)
        assertEquals(original.vars, decoded.vars)
        assertEquals(original.tags, decoded.tags)
        assertEquals(original.isFavorite, decoded.isFavorite)
        assertEquals(original.createdAt, decoded.createdAt)
    }

    @Test
    fun `step parameters, policies and frozen output survive`() {
        val decoded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(sampleFlow()))!!
        val schedule = decoded.steps.first { it.id == "s1" }

        assertEquals("daily", schedule.paramString("frequency"))
        assertEquals(true, schedule.paramBoolean("runOnlyOnWifi"))
        assertEquals(15.0, (schedule.param("interval") as Number).toDouble(), 1e-9)
        assertNull("an explicit null param must stay null, not become the string \"null\"", schedule.param("nothing"))
        assertEquals("keep me", schedule.notes)
        assertEquals(OnErrorPolicy.Continue, schedule.onError)
        assertEquals(3, schedule.retry.maxAttempts)
        assertEquals(30_000L, schedule.timeoutMs)

        val http = decoded.steps.first { it.id == "s2" }
        assertEquals(OnErrorPolicy.Retry, http.onError)
        val headers = http.param("headers") as Map<*, *>
        assertEquals("abc", headers["X-Key"])
        val frozen = http.frozenOutput
        assertNotNull("frozen output was lost", frozen)
        assertEquals(1, frozen!!.size)
        assertEquals("clear", frozen[0].json["sky"])

        val notify = decoded.steps.first { it.id == "s3" }
        assertTrue("disabled flag was lost", notify.disabled)
    }

    @Test
    fun `groups, sticky notes and active deploy flag survive`() {
        val flow = sampleFlow().copy(
            isActive = true,
            groups = persistentListOf(
                FlowGroup(
                    id = "g1",
                    name = "Weather Processing",
                    stepIds = persistentListOf("s1", "s2"),
                    color = "amber",
                    description = "Collects forecast data",
                )
            ),
            notes = persistentListOf(
                StickyNote(
                    id = "n1",
                    title = "Architecture Note",
                    content = "This pipeline runs daily and triggers local push notification.",
                    x = 100f,
                    y = 200f,
                    width = 280f,
                    height = 160f,
                    color = "gold",
                )
            ),
        )
        val decoded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(flow))!!
        assertTrue(decoded.isActive)
        assertEquals(1, decoded.groups.size)
        val group = decoded.groups.first()
        assertEquals("g1", group.id)
        assertEquals("Weather Processing", group.name)
        assertEquals("amber", group.color)
        assertEquals(listOf("s1", "s2"), group.stepIds)

        assertEquals(1, decoded.notes.size)
        val note = decoded.notes.first()
        assertEquals("n1", note.id)
        assertEquals("Architecture Note", note.title)
        assertEquals(100f, note.x, 1e-4f)
        assertEquals(200f, note.y, 1e-4f)
        assertEquals(280f, note.width, 1e-4f)
        assertEquals(160f, note.height, 1e-4f)
    }

    @Test
    fun `streams keep their source port and label`() {
        val decoded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(sampleFlow()))!!
        val second = decoded.streams.first { it.id == "e2" }
        assertEquals(1, second.fromPort)
        assertEquals("ok", second.label)
        assertEquals("s2", second.fromStepId)
        assertEquals("s3", second.toStepId)
    }

    @Test
    fun `unknown fields written by a newer build are ignored, not fatal`() {
        val forward = """
            {
              "id": "flow-9",
              "name": "Future",
              "steps": [
                {"id":"a","type":"spark.manual","name":"Start","brandNewField":{"deep":[1,2,3]}}
              ],
              "streams": [],
              "futureTopLevel": "ignore me",
              "schemaVersion": 99
            }
        """.trimIndent()

        val decoded = JsonCodec.decodeFlow(forward)
        assertNotNull(decoded)
        assertEquals("flow-9", decoded!!.id)
        assertEquals(1, decoded.steps.size)
        assertEquals("spark.manual", decoded.steps[0].type)
    }

    @Test
    fun `corrupt json decodes to null instead of throwing`() {
        assertNull(JsonCodec.decodeFlow("{ this is not json"))
        assertNull(JsonCodec.decodeFlow(""))
        assertNull(JsonCodec.decodeRun("[1,2,3]"))
    }

    @Test
    fun `empty flow round trips`() {
        val empty = Flow(id = "blank", name = "New Flow")
        val decoded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(empty))
        assertNotNull(decoded)
        assertEquals(0, decoded!!.steps.size)
        assertEquals(0, decoded.streams.size)
        assertEquals("New Flow", decoded.name)
    }

    @Test
    fun `run record survives an encode decode round trip`() {
        val run = RunRecord(
            id = "run-1",
            flowId = "flow-1",
            flowName = "Morning Brief",
            status = RunStatus.PartialSuccess,
            startedAt = 1_700_000_000_000,
            finishedAt = 1_700_000_004_200,
            trigger = "Scheduled",
            stepRuns = listOf(
                StepRun(
                    stepId = "s1",
                    stepName = "Every morning",
                    status = StepRunStatus.Succeeded,
                    startedAt = 1_700_000_000_100,
                    finishedAt = 1_700_000_000_400,
                    itemsIn = 1,
                    itemsOut = 1,
                    attempts = 1,
                    output = persistentListOf(FlowData.of("tick" to true)),
                ),
                StepRun(
                    stepId = "s2",
                    stepName = "Fetch weather",
                    status = StepRunStatus.Failed,
                    startedAt = 1_700_000_000_500,
                    finishedAt = 1_700_000_004_000,
                    error = "HTTP 503",
                    attempts = 3,
                ),
            ).toImmutableList(),
            error = "1 Step failed",
        )

        val decoded = JsonCodec.decodeRun(JsonCodec.encodeRun(run))
        assertNotNull(decoded)
        assertEquals(run.status, decoded!!.status)
        assertEquals(run.trigger, decoded.trigger)
        assertEquals(run.error, decoded.error)
        assertEquals(2, decoded.stepRuns.size)
        assertEquals(StepRunStatus.Failed, decoded.stepRuns[1].status)
        assertEquals("HTTP 503", decoded.stepRuns[1].error)
        assertEquals(3, decoded.stepRuns[1].attempts)
        assertEquals(4_200L, decoded.durationMs)
    }

    @Test
    fun `unknown run status falls back instead of dropping the whole history row`() {
        val weird = """
            {"id":"r1","flowId":"f","flowName":"X","status":"Exploded","startedAt":1,"stepRuns":[]}
        """.trimIndent()
        val decoded = JsonCodec.decodeRun(weird)
        assertNotNull("one odd status should not wipe a Run from history", decoded)
        assertEquals(RunStatus.Failed, decoded!!.status)
    }

    @Test
    fun `item lineage and structured error survive a round trip`() {
        val item = FlowData(
            json = mapOf("a" to 1),
            error = com.kaivoflow.core.model.ItemError("bad row", "s2", "item", 99L),
            sources = listOf(com.kaivoflow.core.model.ItemRef("s1", 0, 3, port = 1)),
        )
        val back = JsonCodec.decodeFlowData(JsonCodec.encodeFlowData(item))
        assertEquals(item.error, back.error)
        assertEquals(item.sources, back.sources)
        // Old app versions read these two fields; they must still be written.
        val encoded = JsonCodec.encodeFlowData(item)
        assertEquals("bad row", (encoded["error"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(3, (encoded["pairedIndex"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun `items stored before lineage existed still decode`() {
        val legacyWrapped = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"json":{"a":1},"pairedIndex":2,"error":"old failure"}"""
        )
        val item = JsonCodec.decodeFlowData(legacyWrapped)
        assertEquals(1, (item.json["a"] as Number).toInt())
        assertEquals(2, item.pairedIndex)
        assertEquals("old failure", item.error!!.message)
        assertEquals("legacy", item.error!!.kind)
        // The even older bare-json shape.
        val bare = JsonCodec.decodeFlowData(kotlinx.serialization.json.Json.parseToJsonElement("""{"a":5}"""))
        assertEquals(5, (bare.json["a"] as Number).toInt())
        assertTrue(bare.sources.isEmpty())
    }
}
