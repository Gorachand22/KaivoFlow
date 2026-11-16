package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.LifecycleEffect as Fx
import com.kaivoflow.core.engine.LifecycleEvent as Ev
import com.kaivoflow.core.engine.LifecycleState.Draft
import com.kaivoflow.core.engine.LifecycleState.Error
import com.kaivoflow.core.engine.LifecycleState.Paused
import com.kaivoflow.core.engine.LifecycleState.Published
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationStateMachineTest {

    private val t0 = 1_000_000L
    private val transient = RegistrationFailure("s1", FailureCode.PortInUse, "port 8080 busy")
    private val permanent = RegistrationFailure("s1", FailureCode.PermissionMissing)

    private fun snap(state: LifecycleState) = when (state) {
        Draft -> LifecycleSnapshot()
        Published -> LifecycleSnapshot(Published, publishedRevision = 3, since = t0 - 1)
        Paused -> LifecycleSnapshot(Paused, publishedRevision = 3, since = t0 - 5_000, pausedUntil = t0 + 100_000)
        Error -> LifecycleSnapshot(
            Error, publishedRevision = 3, since = t0 - 1, errorCode = "PortInUse", errorSince = t0 - 1,
            retryCount = 1, nextRetryAt = t0 + 5_000,
        )
    }

    private enum class Out { Moved, Refused, Ignored, Illegal, Deleted }

    private fun out(r: TransitionResult) = when (r) {
        is TransitionResult.Moved -> Out.Moved
        is TransitionResult.Refused -> Out.Refused
        is TransitionResult.Ignored -> Out.Ignored
        is TransitionResult.Illegal -> Out.Illegal
        is TransitionResult.Deleted -> Out.Deleted
    }

    private val events: Map<String, Ev> = linkedMapOf(
        "publish" to Ev.Publish(true, emptyList(), 4),
        "publishChanges" to Ev.PublishChanges(true, emptyList(), 4),
        "pause" to Ev.Pause(null),
        "resume" to Ev.Resume(true, emptyList()),
        "armOk" to Ev.ArmReport(emptyList()),
        "armFail" to Ev.ArmReport(listOf(transient)),
        "unpublish" to Ev.Unpublish(),
        "delete" to Ev.Delete(),
    )

    /** Every cell of the state x event table of spec 4.2: outcome and resulting state. */
    private val table: Map<LifecycleState, Map<String, Pair<Out, LifecycleState?>>> = mapOf(
        Draft to mapOf(
            "publish" to (Out.Moved to Published), "publishChanges" to (Out.Illegal to null), "pause" to (Out.Illegal to null),
            "resume" to (Out.Illegal to null), "armOk" to (Out.Ignored to null), "armFail" to (Out.Ignored to null),
            "unpublish" to (Out.Illegal to null), "delete" to (Out.Deleted to null),
        ),
        Published to mapOf(
            "publish" to (Out.Illegal to null), "publishChanges" to (Out.Moved to Published), "pause" to (Out.Moved to Paused),
            "resume" to (Out.Illegal to null), "armOk" to (Out.Ignored to null), "armFail" to (Out.Moved to Error),
            "unpublish" to (Out.Moved to Draft), "delete" to (Out.Deleted to null),
        ),
        Paused to mapOf(
            "publish" to (Out.Illegal to null), "publishChanges" to (Out.Illegal to null), "pause" to (Out.Illegal to null),
            "resume" to (Out.Moved to Published), "armOk" to (Out.Ignored to null), "armFail" to (Out.Ignored to null),
            "unpublish" to (Out.Moved to Draft), "delete" to (Out.Deleted to null),
        ),
        Error to mapOf(
            "publish" to (Out.Illegal to null), "publishChanges" to (Out.Illegal to null), "pause" to (Out.Moved to Paused),
            "resume" to (Out.Illegal to null), "armOk" to (Out.Moved to Published), "armFail" to (Out.Moved to Error),
            "unpublish" to (Out.Moved to Draft), "delete" to (Out.Deleted to null),
        ),
    )

    @Test fun `table covers every state and every event`() {
        assertEquals(LifecycleState.entries.toSet(), table.keys)
        table.values.forEach { assertEquals(events.keys, it.keys) }
    }

    @Test fun `every cell of the transition table behaves as specified`() {
        for ((state, row) in table) for ((name, expected) in row) {
            val r = ActivationStateMachine.reduce(snap(state), events.getValue(name), t0)
            assertEquals("$state + $name", expected.first, out(r))
            if (r is TransitionResult.Moved) assertEquals("$state + $name target", expected.second, r.snapshot.state)
            if (expected.first == Out.Illegal || expected.first == Out.Ignored) assertEquals("$state + $name unchanged", snap(state), r.snapshot)
        }
    }

    @Test fun `state changes write a log row with actor and reason, self transition in Error does not`() {
        val publish = ActivationStateMachine.reduce(snap(Draft), Ev.Publish(true, emptyList(), 1), t0) as TransitionResult.Moved
        assertEquals(LifecycleLogEntry(t0, Draft, Published, LifecycleReason.Published, LifecycleActor.User), publish.log)
        val fail = ActivationStateMachine.reduce(snap(Error), Ev.ArmReport(listOf(transient)), t0) as TransitionResult.Moved
        assertNull(fail.log)
        val pc = ActivationStateMachine.reduce(snap(Published), Ev.PublishChanges(true, emptyList(), 4, LifecycleActor.Import), t0) as TransitionResult.Moved
        assertEquals(LifecycleReason.PublishedChanges, pc.log!!.reason)
        assertEquals("import", pc.log!!.actor.db)
    }

    // ---- Draft -> Published and refusals ---------------------------------------------------

    @Test fun `publish arms everything, snapshots the revision and starts the engine check`() {
        val r = ActivationStateMachine.reduce(snap(Draft), Ev.Publish(true, emptyList(), 1), t0) as TransitionResult.Moved
        assertEquals(1, r.snapshot.publishedRevision)
        assertEquals(listOf(Fx.SnapshotRevision(1), Fx.ArmAll, Fx.ReassessEngine), r.effects)
    }

    @Test fun `publish refused for validation errors and for arm failures leaves Draft untouched`() {
        val v = ActivationStateMachine.reduce(snap(Draft), Ev.Publish(false, emptyList(), 1), t0) as TransitionResult.Refused
        assertEquals(RefusalReason.ValidationFailed, v.reason)
        assertTrue(v.effects.isEmpty())
        val a = ActivationStateMachine.reduce(snap(Draft), Ev.Publish(true, listOf(permanent), 1), t0) as TransitionResult.Refused
        assertEquals(RefusalReason.ArmFailed, a.reason)
        assertEquals(listOf(permanent), a.failures)
        assertEquals(snap(Draft), a.snapshot)
    }

    @Test fun `publish changes refused keeps revision N running`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.PublishChanges(true, listOf(transient), 4), t0) as TransitionResult.Refused
        assertEquals(3, r.snapshot.publishedRevision)
        assertEquals(Published, r.snapshot.state)
    }

    @Test fun `publish changes swaps sparks and bumps the revision`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.PublishChanges(true, emptyList(), 4), t0) as TransitionResult.Moved
        assertEquals(4, r.snapshot.publishedRevision)
        assertEquals(listOf(Fx.SnapshotRevision(4), Fx.SwapSparks, Fx.ReassessEngine), r.effects)
    }

    // ---- Pause and resume ------------------------------------------------------------------

    @Test fun `timed pause schedules the resume alarm and disarms`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.Pause(t0 + 8 * 3_600_000L), t0) as TransitionResult.Moved
        assertEquals(t0 + 8 * 3_600_000L, r.snapshot.pausedUntil)
        assertTrue(Fx.DisarmAll in r.effects)
        assertTrue(Fx.ScheduleResume(t0 + 8 * 3_600_000L) in r.effects)
        assertEquals(LifecycleReason.UserPaused, r.snapshot.reason)
    }

    @Test fun `open ended pause has no resume alarm and auto pause keeps its reason`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.Pause(null, LifecycleReason.AutoPaused, LifecycleActor.Engine), t0) as TransitionResult.Moved
        assertTrue(r.effects.none { it is Fx.ScheduleResume })
        assertEquals(LifecycleReason.AutoPaused, r.log!!.reason)
    }

    @Test fun `pause in the past is refused`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.Pause(t0), t0)
        assertEquals(RefusalReason.PauseInPast, (r as TransitionResult.Refused).reason)
    }

    @Test fun `pausing from Error cancels retries`() {
        val r = ActivationStateMachine.reduce(snap(Error), Ev.Pause(null), t0) as TransitionResult.Moved
        assertTrue(Fx.CancelRetry in r.effects)
        assertNull(r.snapshot.nextRetryAt)
        assertNull(r.snapshot.errorCode)
    }

    @Test fun `resume re-arms and marks the paused span, failed guard keeps Paused`() {
        val r = ActivationStateMachine.reduce(snap(Paused), Ev.Resume(true, emptyList()), t0) as TransitionResult.Moved
        assertEquals(Published, r.snapshot.state)
        assertNull(r.snapshot.pausedUntil)
        assertTrue(Fx.MarkExpectedRunsPaused(from = t0 - 5_000, to = t0) in r.effects)
        assertTrue(Fx.ArmAll in r.effects)
        val bad = ActivationStateMachine.reduce(snap(Paused), Ev.Resume(false, emptyList()), t0) as TransitionResult.Refused
        assertEquals(Paused, bad.snapshot.state)
        val bad2 = ActivationStateMachine.reduce(snap(Paused), Ev.Resume(true, listOf(permanent)), t0)
        assertEquals(RefusalReason.ArmFailed, (bad2 as TransitionResult.Refused).reason)
    }

    @Test fun `timer helpers`() {
        assertTrue(ActivationStateMachine.resumeDue(snap(Paused), t0 + 100_000))
        assertTrue(!ActivationStateMachine.resumeDue(snap(Paused), t0 + 99_999))
        assertTrue(!ActivationStateMachine.resumeDue(snap(Paused).copy(pausedUntil = null), t0 + 1_000_000))
        assertTrue(ActivationStateMachine.retryDue(snap(Error), t0 + 5_000))
        assertTrue(!ActivationStateMachine.retryDue(snap(Error), t0 + 4_999))
        assertTrue(!ActivationStateMachine.retryDue(snap(Error).copy(nextRetryAt = null), t0 + 1_000_000))
        assertTrue(!ActivationStateMachine.retryDue(snap(Published), t0 + 1_000_000))
    }

    // ---- Unpublish and delete --------------------------------------------------------------

    @Test fun `unpublish drops queued rows, clears the revision and disarms`() {
        for (s in listOf(Published, Paused, Error)) {
            val r = ActivationStateMachine.reduce(snap(s), Ev.Unpublish(), t0) as TransitionResult.Moved
            assertEquals(Draft, r.snapshot.state)
            assertNull(r.snapshot.publishedRevision)
            assertTrue(Fx.DropInboxRows("Unpublished") in r.effects)
            assertTrue(Fx.DisarmAll in r.effects && Fx.CancelRetry in r.effects && Fx.CancelResume in r.effects)
            assertEquals(LifecycleReason.Unpublished, r.snapshot.reason)
        }
    }

    @Test fun `unpublish with pending rows needs confirmation`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.Unpublish(pendingInboxRows = 2, confirmed = false), t0)
        assertEquals(RefusalReason.NeedsConfirmation, (r as TransitionResult.Refused).reason)
        val ok = ActivationStateMachine.reduce(snap(Published), Ev.Unpublish(pendingInboxRows = 2, confirmed = true), t0)
        assertTrue(ok is TransitionResult.Moved)
    }

    @Test fun `delete disarms, drops rows and is allowed from every state`() {
        for (s in LifecycleState.entries) {
            val r = ActivationStateMachine.reduce(snap(s), Ev.Delete(), t0) as TransitionResult.Deleted
            assertTrue(Fx.DropInboxRows("Deleted") in r.effects && Fx.DisarmAll in r.effects)
        }
    }

    @Test fun `import always starts as Draft`() {
        val s = ActivationStateMachine.initialImported(t0)
        assertEquals(Draft, s.state)
        assertEquals(LifecycleReason.Imported, s.reason)
        assertNull(s.publishedRevision)
    }

    // ---- Error handling, retry and backoff -------------------------------------------------

    @Test fun `backoff delays are 5s 15s 1m 5m 15m 1h and stay at 1h`() {
        val expected = listOf(5_000L, 15_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 3_600_000L, 3_600_000L)
        assertEquals(expected, (0..7).map { RetryBackoff.delayMs(it) })
        assertEquals(5_000L, RetryBackoff.delayMs(-3))
    }

    @Test fun `repeated transient failures walk the backoff table`() {
        var s = snap(Published)
        var now = t0
        val expected = listOf(5_000L, 15_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 3_600_000L)
        expected.forEachIndexed { i, delay ->
            val r = ActivationStateMachine.reduce(s, Ev.ArmReport(listOf(transient)), now) as TransitionResult.Moved
            assertEquals("attempt $i", now + delay, r.snapshot.nextRetryAt)
            assertEquals(i + 1, r.snapshot.retryCount)
            assertTrue(Fx.ScheduleRetry(now + delay) in r.effects)
            s = r.snapshot
            now = s.nextRetryAt!!
        }
        assertEquals(Error, s.state)
    }

    @Test fun `entering Error records the cause, notifies and keeps errorSince through retries`() {
        val first = ActivationStateMachine.reduce(snap(Published), Ev.ArmReport(listOf(transient)), t0) as TransitionResult.Moved
        assertEquals("PortInUse", first.snapshot.errorCode)
        assertEquals(t0, first.snapshot.errorSince)
        assertTrue(Fx.NotifyEnteredError("PortInUse") in first.effects)
        val second = ActivationStateMachine.reduce(first.snapshot, Ev.ArmReport(listOf(transient)), t0 + 5_000) as TransitionResult.Moved
        assertEquals(t0, second.snapshot.errorSince)
        assertTrue(second.effects.none { it is Fx.NotifyEnteredError })
    }

    @Test fun `permanent failures are not retried and wait for a fix`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.ArmReport(listOf(permanent)), t0) as TransitionResult.Moved
        assertEquals(Error, r.snapshot.state)
        assertNull(r.snapshot.nextRetryAt)
        assertEquals(0, r.snapshot.retryCount)
        assertTrue(r.effects.none { it is Fx.ScheduleRetry })
        for (code in listOf(FailureCode.PermissionMissing, FailureCode.InvalidConfig, FailureCode.CredentialMissing)) {
            assertTrue(code.permanent)
            val x = ActivationStateMachine.reduce(snap(Published), Ev.ArmReport(listOf(RegistrationFailure("s", code))), t0) as TransitionResult.Moved
            assertNull(x.snapshot.nextRetryAt)
        }
        assertTrue(FailureCode.entries.filter { !it.permanent }.size >= 5)
        // A later permanent report in Error keeps it retry-less, and the fix (empty report) recovers.
        val again = ActivationStateMachine.reduce(r.snapshot, Ev.ArmReport(listOf(permanent)), t0 + 1) as TransitionResult.Moved
        assertNull(again.snapshot.nextRetryAt)
        assertTrue(ActivationStateMachine.reduce(again.snapshot, Ev.ArmReport(emptyList()), t0 + 2) is TransitionResult.Moved)
    }

    @Test fun `mixed failures retry for the transient part and report the permanent cause`() {
        val r = ActivationStateMachine.reduce(snap(Published), Ev.ArmReport(listOf(transient, permanent)), t0) as TransitionResult.Moved
        assertEquals("PermissionMissing", r.snapshot.errorCode)
        assertEquals(t0 + 5_000, r.snapshot.nextRetryAt)
    }

    @Test fun `recovery returns to Published, keeps the counter, and notifies only after 5 minutes`() {
        val inError = snap(Error).copy(errorSince = t0, retryCount = 3, nextRetryAt = t0 + 1)
        val quick = ActivationStateMachine.reduce(inError, Ev.ArmReport(emptyList()), t0 + 300_000) as TransitionResult.Moved
        assertEquals(Published, quick.snapshot.state)
        assertEquals(3, quick.snapshot.retryCount)
        assertEquals(t0 + 300_000, quick.snapshot.healthySince)
        assertNull(quick.snapshot.errorCode)
        assertNull(quick.snapshot.nextRetryAt)
        assertTrue(quick.effects.none { it == Fx.NotifyBackToNormal })
        assertTrue(Fx.CancelRetry in quick.effects)
        val slow = ActivationStateMachine.reduce(inError, Ev.ArmReport(emptyList()), t0 + 300_001) as TransitionResult.Moved
        assertTrue(Fx.NotifyBackToNormal in slow.effects)
    }

    @Test fun `counter resets only after a 10 minute stable period`() {
        val recovered = snap(Published).copy(retryCount = 3, healthySince = t0)
        // Fails again 9m59s after recovering: backoff continues with the 4th delay (5 min).
        val early = ActivationStateMachine.reduce(recovered, Ev.ArmReport(listOf(transient)), t0 + 599_999) as TransitionResult.Moved
        assertEquals(t0 + 599_999 + 300_000, early.snapshot.nextRetryAt)
        assertEquals(4, early.snapshot.retryCount)
        // Fails after exactly 10 minutes: counter was reset, first delay again.
        val late = ActivationStateMachine.reduce(recovered, Ev.ArmReport(listOf(transient)), t0 + 600_000) as TransitionResult.Moved
        assertEquals(t0 + 600_000 + 5_000, late.snapshot.nextRetryAt)
        assertEquals(1, late.snapshot.retryCount)
        assertEquals(0, RetryBackoff.effectiveCount(9, t0, t0 + 600_000))
        assertEquals(9, RetryBackoff.effectiveCount(9, t0, t0 + 599_999))
        assertEquals(9, RetryBackoff.effectiveCount(9, null, t0))
    }

    @Test fun `isLive covers Published and Error only`() {
        assertEquals(listOf(Published, Error), LifecycleState.entries.filter { it.isLive })
    }

    @Test fun `db spellings round trip and unknown values fall back`() {
        LifecycleState.entries.forEach { assertEquals(it, LifecycleState.fromDb(it.db)) }
        LifecycleReason.entries.forEach { assertEquals(it, LifecycleReason.fromDb(it.db)) }
        assertEquals("PUBLISHED", Published.db)
        assertEquals("MIGRATED_ACTIVE", LifecycleReason.MigratedActive.db)
        assertEquals("PUBLISHED_CHANGES", LifecycleReason.PublishedChanges.db)
        assertEquals(Draft, LifecycleState.fromDb("garbage"))
        assertEquals(Draft, LifecycleState.fromDb(null))
    }
}
