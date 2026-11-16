package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationCoordinatorTest {
    private class Stub(override val spec: NodeSpec) : StepExecutor {
        override suspend fun execute(ctx: StepContext) = StepResult(mapOf(0 to ctx.input))
    }
    private fun trig(id: String) = Stub(NodeSpec(id, id, StepCategory.Trigger, "t", "i", kind = NodeKind.Trigger, canStartFlow = true))

    private val validator = PublishValidator(FlowValidator(registry(*(SparkTypes.all - SparkTypes.MANUAL).map { trig(it) }.toTypedArray(), Stub(NodeSpec("net.http", "net.http", StepCategory.Transform, "t", "i")))))

    private class Store : ActivationStore {
        val drafts = HashMap<String, Flow>()
        val snaps = HashMap<String, LifecycleSnapshot>()
        val revisions = HashMap<Pair<String, Int>, Flow>()
        val logs = ArrayList<LifecycleLogEntry>()
        var pending = 0
        override suspend fun draft(flowId: String) = drafts[flowId]
        override suspend fun snapshot(flowId: String) = snaps[flowId]
        override suspend fun allSnapshots(): Map<String, LifecycleSnapshot> = snaps.toMap()
        override suspend fun published(flowId: String, revision: Int) = revisions[flowId to revision]
        override suspend fun latestRevision(flowId: String) = revisions.keys.filter { it.first == flowId }.maxOfOrNull { it.second } ?: 0
        override suspend fun otherLiveFlows(excluding: String) = emptyList<Flow>()
        override suspend fun pendingInboxRows(flowId: String) = pending
        override suspend fun commit(flowId: String, snapshot: LifecycleSnapshot, log: LifecycleLogEntry?, revision: PublishedRevision?): Boolean {
            snaps[flowId] = snapshot
            log?.let { logs += it }
            revision?.let { revisions[flowId to it.revision] = it.flow }
            return true
        }
    }

    private class Platform : ActivationPlatform {
        val calls = ArrayList<String>()
        val lines = ArrayList<String>()
        var armResult: (Flow) -> Map<String, SparkRegState> = { f -> f.triggers.associate { it.id to SparkRegState.armed() } }
        var throwOnArm = false
        override suspend fun facts() = PlatformFacts.AllGood
        override suspend fun arm(flow: Flow): Map<String, SparkRegState> {
            calls += "arm:${flow.id}:${flow.steps.size}"
            if (throwOnArm) error("boom")
            return armResult(flow)
        }
        override suspend fun disarmRemoved(old: Flow, new: Flow) { calls += "disarmRemoved" }
        override suspend fun disarm(flowId: String) { calls += "disarm:$flowId" }
        override suspend fun scheduleRetry(flowId: String, at: Long) { calls += "retry:$at" }
        override suspend fun cancelRetry(flowId: String) { calls += "cancelRetry" }
        override suspend fun scheduleResume(flowId: String, at: Long) { calls += "resume:$at" }
        override suspend fun cancelResume(flowId: String) { calls += "cancelResume" }
        override suspend fun dropInbox(flowId: String, reason: String) { calls += "drop:$reason" }
        override suspend fun markExpectedPaused(flowId: String, from: Long, to: Long) { calls += "paused:$from-$to" }
        override suspend fun recordFailures(flowId: String, failures: List<RegistrationFailure>) { calls += "fail:${failures.size}" }
        override suspend fun notifyEnteredError(flowId: String, code: String) { calls += "notifyError:$code" }
        override suspend fun notifyBackToNormal(flowId: String) { calls += "back" }
        override suspend fun reassessEngine() { calls += "reassess" }
        override suspend fun purge(flowId: String) { calls += "purge" }
        override fun log(line: String) { lines += line; calls += "log" }
    }

    private var now = 1_000_000L
    private val store = Store()
    private val platform = Platform()
    private val c = ActivationCoordinator(store, platform, validator, { now }, batchSize = 2)

    private val schedule = step("sp", SparkTypes.SCHEDULE, "Morning", mapOf("frequency" to "daily", "time" to "08:00", "timezone" to "UTC"))
    private fun flow(id: String = "f", vararg extra: com.kaivoflow.core.model.StepNode) =
        Flow(id = id, name = id, steps = persistentListOf(schedule, *extra))

    private fun run(b: suspend () -> ActivationResult) = runBlocking { b() }

    @Test fun `publish arms then commits revision one and a log row`() {
        store.drafts["f"] = flow()
        store.snaps["f"] = LifecycleSnapshot()
        val r = run { c.publish("f") }
        assertEquals(ActivationStatus.Done, r.status)
        assertEquals(LifecycleState.Published, store.snaps["f"]!!.state)
        assertEquals(1, store.snaps["f"]!!.publishedRevision)
        assertNotNull(store.revisions["f" to 1])
        assertEquals(1, store.logs.size)
        assertTrue(platform.calls.indexOf("arm:f:1") < platform.calls.indexOf("reassess"))
    }

    @Test fun `validation errors block and arm nothing`() {
        store.drafts["f"] = Flow(id = "f", name = "f", steps = persistentListOf(step("sp", SparkTypes.SCHEDULE, "Bad", mapOf("time" to "99:99"))))
        store.snaps["f"] = LifecycleSnapshot()
        val r = run { c.publish("f") }
        assertEquals(ActivationStatus.Blocked, r.status)
        assertTrue(platform.calls.none { it.startsWith("arm") })
        assertEquals(LifecycleState.Draft, store.snaps["f"]!!.state)
    }

    @Test fun `an arm failure refuses the publish and disarms`() {
        store.drafts["f"] = flow()
        store.snaps["f"] = LifecycleSnapshot()
        platform.armResult = { mapOf("sp" to SparkRegState.failed(FailureCode.PermissionMissing, "SMS")) }
        val r = run { c.publish("f") }
        assertEquals(ActivationStatus.ArmFailed, r.status)
        assertEquals(LifecycleState.Draft, store.snaps["f"]!!.state)
        assertTrue("disarm:f" in platform.calls)
        assertTrue(store.revisions.isEmpty())
        assertEquals(FailureCode.PermissionMissing, RegistrationFailure("sp", FailureCode.PermissionMissing).code)
        assertFalse(r.sparks["sp"]!!.isArmed)
    }

    @Test fun `an arming exception becomes a failure, never a crash`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        platform.throwOnArm = true
        assertEquals(ActivationStatus.ArmFailed, run { c.publish("f") }.status)
    }

    @Test fun `warnings need acknowledgement`() {
        // A schedule step with a side effect and no approval produces no warning in this tiny registry, so use platform facts.
        val warned = ActivationCoordinator(store, object : ActivationPlatform by platform {
            override suspend fun facts() = PlatformFacts.AllGood.copy(batteryOptimisationExempt = false)
        }, validator, { now })
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        val first = runBlocking { warned.publish("f") }
        assertEquals(ActivationStatus.NeedsAcknowledgement, first.status)
        assertTrue(first.report!!.warnings.isNotEmpty())
        assertEquals(ActivationStatus.Done, runBlocking { warned.publish("f", acknowledgeWarnings = true) }.status)
    }

    @Test fun `editing a published flow changes nothing live until publish changes`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        store.drafts["f"] = flow("f", step("n2", "net.http", "Call"))
        val prev = runBlocking { c.preview("f") }!!
        assertTrue(prev.hasUnpublishedChanges)
        assertEquals(1, store.snaps["f"]!!.publishedRevision)
        assertEquals(1, store.revisions["f" to 1]!!.steps.size)
    }

    @Test fun `publish changes arms the new set first then disarms removed, revision two`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        platform.calls.clear()
        store.drafts["f"] = flow("f", step("n2", "net.http", "Call"))
        val r = run { c.publishChanges("f", acknowledgeWarnings = true) }
        assertEquals(ActivationStatus.Done, r.status)
        assertEquals(2, store.snaps["f"]!!.publishedRevision)
        assertEquals(listOf("log", "arm:f:2", "disarmRemoved"), platform.calls.take(3))
    }

    @Test fun `failed swap keeps revision N and re-arms it`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        platform.calls.clear()
        store.drafts["f"] = flow("f", step("n2", "net.http", "Call"))
        platform.armResult = { f -> if (f.steps.size == 2) mapOf("sp" to SparkRegState.failed(FailureCode.PortInUse, "busy")) else mapOf("sp" to SparkRegState.armed()) }
        val r = run { c.publishChanges("f", acknowledgeWarnings = true) }
        assertEquals(ActivationStatus.ArmFailed, r.status)
        assertEquals(1, store.snaps["f"]!!.publishedRevision)
        assertEquals("arm:f:1", platform.calls.last())
        assertFalse("disarmRemoved" in platform.calls)
    }

    @Test fun `unchanged draft is a no-op`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        assertEquals(ActivationStatus.Unchanged, run { c.publishChanges("f", acknowledgeWarnings = true) }.status)
    }

    @Test fun `pause and timed resume`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        val r = run { c.pause("f", until = now + 8 * 3_600_000L) }
        assertEquals(LifecycleState.Paused, store.snaps["f"]!!.state)
        assertTrue(platform.calls.any { it.startsWith("resume:") } && "disarm:f" in platform.calls)
        assertEquals(now + 8 * 3_600_000L, r.snapshot!!.pausedUntil)
        now += 9 * 3_600_000L
        assertEquals(1, runBlocking { c.runDue() })
        assertEquals(LifecycleState.Published, store.snaps["f"]!!.state)
        assertTrue(platform.calls.any { it.startsWith("paused:") })
    }

    @Test fun `unpublish needs confirmation when inbox rows are pending, then drops them`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        store.pending = 3
        assertEquals(ActivationStatus.Refused, run { c.unpublish("f") }.status)
        assertEquals(ActivationStatus.Done, run { c.unpublish("f", confirmed = true) }.status)
        assertEquals(LifecycleState.Draft, store.snaps["f"]!!.state)
        assertTrue("drop:Unpublished" in platform.calls)
    }

    @Test fun `a failing spark moves to Error with backoff and recovers by itself`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        platform.armResult = { mapOf("sp" to SparkRegState.failed(FailureCode.AlarmRefused, "x")) }
        run { c.reportArming("f") }
        val s = store.snaps["f"]!!
        assertEquals(LifecycleState.Error, s.state)
        assertEquals(now + 5_000, s.nextRetryAt)
        assertEquals(now + 5_000, runBlocking { c.nextDueAt() })
        assertEquals(0, runBlocking { c.runDue() }) // not due yet
        now += 6_000
        assertEquals(1, runBlocking { c.runDue() }) // still failing: second backoff step
        assertEquals(now + 15_000, store.snaps["f"]!!.nextRetryAt)
        platform.armResult = { f -> f.triggers.associate { it.id to SparkRegState.armed() } }
        now += 16_000
        runBlocking { c.runDue() }
        assertEquals(LifecycleState.Published, store.snaps["f"]!!.state)
        assertNull(runBlocking { c.nextDueAt() })
    }

    @Test fun `a permanent failure is not retried`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        platform.armResult = { mapOf("sp" to SparkRegState.failed(FailureCode.PermissionMissing, "SMS")) }
        run { c.reportArming("f") }
        assertEquals(LifecycleState.Error, store.snaps["f"]!!.state)
        assertNull(store.snaps["f"]!!.nextRetryAt)
        now += 10 * 3_600_000L
        assertEquals(0, runBlocking { c.runDue() })
    }

    @Test fun `restore re-arms live flows in batches with a log line before each registration`() {
        for (i in 1..5) {
            store.drafts["f$i"] = flow("f$i"); store.snaps["f$i"] = LifecycleSnapshot()
            run { c.publish("f$i") }
        }
        store.drafts["d"] = flow("d"); store.snaps["d"] = LifecycleSnapshot() // draft: not restored
        platform.calls.clear(); platform.lines.clear()
        val rep = runBlocking { c.restoreAtStart() }
        assertEquals(5, rep.considered); assertEquals(3, rep.batches); assertEquals(5, rep.restored)
        assertEquals(5, platform.lines.size)
        assertTrue(platform.lines.all { it.startsWith("restore: registering f") })
        for (i in 1..5) assertTrue(platform.calls.indexOf("log") < platform.calls.indexOf("arm:f$i:1"))
        assertEquals("reassess", platform.calls.last())
    }

    @Test fun `restore sends a flow whose spark cannot arm to Error and continues with the rest`() {
        for (i in 1..3) { store.drafts["f$i"] = flow("f$i"); store.snaps["f$i"] = LifecycleSnapshot(); run { c.publish("f$i") } }
        platform.armResult = { f -> if (f.id == "f2") mapOf("sp" to SparkRegState.failed(FailureCode.PortInUse, "busy")) else f.triggers.associate { it.id to SparkRegState.armed() } }
        val rep = runBlocking { c.restoreAtStart() }
        assertEquals(1, rep.errored); assertEquals(2, rep.restored)
        assertEquals(LifecycleState.Error, store.snaps["f2"]!!.state)
        assertEquals(LifecycleState.Published, store.snaps["f3"]!!.state)
    }

    @Test fun `restore arms the published revision, not the edited draft`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot()
        run { c.publish("f") }
        store.drafts["f"] = flow("f", step("n2", "net.http", "Call"))
        platform.calls.clear()
        runBlocking { c.restoreAtStart() }
        assertTrue("arm:f:1" in platform.calls)
    }

    @Test fun `delete disarms drops inbox and purges`() {
        store.drafts["f"] = flow(); store.snaps["f"] = LifecycleSnapshot(); run { c.publish("f") }
        platform.calls.clear()
        runBlocking { c.deleted("f") }
        assertTrue(listOf("disarm:f", "drop:Deleted", "purge").all { it in platform.calls })
    }

    @Test fun `change detector ignores node position and notes`() {
        val a = flow()
        val moved = a.copy(steps = persistentListOf(a.steps[0].copy(x = 99f, y = 5f, notes = "n")))
        assertFalse(ChangeDetector.differs(moved, a))
        assertTrue(ChangeDetector.differs(flow("f", step("n2", "net.http", "Call")), a))
    }
}
