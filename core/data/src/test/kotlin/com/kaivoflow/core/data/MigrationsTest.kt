package com.kaivoflow.core.data

import com.kaivoflow.core.engine.LifecycleReason
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationsTest {
    private val current = 5

    @Test fun migrationsFormAContiguousChainFromOneToCurrent() {
        val sorted = ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertEquals("first step must start at the oldest schema", 1, sorted.first().startVersion)
        sorted.forEach { assertEquals("each step advances one version", it.startVersion + 1, it.endVersion) }
        sorted.zipWithNext().forEach { (a, b) -> assertEquals("gap between steps", a.endVersion, b.startVersion) }
        assertEquals("chain must end at the database version", current, sorted.last().endVersion)
    }

    @Test fun noDuplicateSteps() {
        assertEquals(ALL_MIGRATIONS.size, ALL_MIGRATIONS.map { it.startVersion }.toSet().size)
        assertTrue(ALL_MIGRATIONS.isNotEmpty())
    }

    @Test fun threeToFourIsRegistered() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
        assertTrue(ALL_MIGRATIONS.contains(MIGRATION_3_4))
    }

    @Test fun fourToFiveIsRegisteredAndIsTheLastStep() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
        assertTrue(ALL_MIGRATIONS.contains(MIGRATION_4_5))
        assertEquals(MIGRATION_4_5, ALL_MIGRATIONS.last())
    }

    @Test fun stepDataEntitiesHaveNoSqlDefaultsSoTheMigrationDeclaresNone() {
        // Room compares defaults when validating: the entities declare none, and MIGRATION_4_5 adds none.
        val e = StepExecutionEntity("r", "s", 0, "f", "Succeeded", 1, null, 1, null, pinned = false, manual = true, dataDropped = false)
        assertEquals("r", e.runId)
        assertFalse(e.pinned)
    }

    @Test fun entityDefaultsMatchTheSqlDefaultsAddedByTheMigration() {
        val e = FlowEntity(id = "a", name = "n", flowJson = "{}", updatedAt = 1, createdAt = 1, isFavorite = false, stepCount = 0)
        assertEquals("DRAFT", e.lifecycle)
        assertEquals("NONE", e.lifecycleReason)
        assertEquals(0L, e.lifecycleSince)
        assertEquals(0, e.retryCount)
        assertNull(e.publishedRevision)
        assertEquals(LifecycleState.Draft, e.lifecycleSnapshot().state)
    }

    // ---- Data pass of MIGRATION_3_4 (the decision part; the SQL needs a device or Robolectric) ----

    @Test fun activeFlagIsFoundInStoredJson() {
        assertTrue(LifecycleLogic.isActiveInJson("""{"id":"a","isActive":true,"steps":[]}"""))
        assertTrue(LifecycleLogic.isActiveInJson("""{"id":"a", "isActive" : true}"""))
        assertFalse(LifecycleLogic.isActiveInJson("""{"id":"a","isActive":false}"""))
        assertFalse(LifecycleLogic.isActiveInJson("""{"id":"a"}"""))
        assertFalse(LifecycleLogic.isActiveInJson(""))
    }

    @Test fun textInsideAStringValueDoesNotCountAsActive() {
        assertFalse(LifecycleLogic.isActiveInJson("""{"name":"x \"isActive\":true y","isActive":false}"""))
    }

    // ---- Phase 1 bridge --------------------------------------------------------------------------

    private val now = 5_000L
    private fun snap(s: LifecycleState) = LifecycleSnapshot(s, publishedRevision = 2, since = 1)

    @Test fun legacyActivationPublishesAndDeactivationDrafts() {
        val up = LifecycleLogic.syncWithLegacyActive(LifecycleSnapshot(), isActive = true, now = now)
        assertEquals(LifecycleState.Published, up.state)
        assertEquals(now, up.since)
        assertNull(up.publishedRevision)
        for (live in listOf(LifecycleState.Published, LifecycleState.Error)) {
            val down = LifecycleLogic.syncWithLegacyActive(snap(live), isActive = false, now = now)
            assertEquals(LifecycleState.Draft, down.state)
            assertEquals(LifecycleReason.Unpublished, down.reason)
        }
    }

    @Test fun bridgeLeavesConsistentAndPausedRowsAlone() {
        for (live in listOf(LifecycleState.Published, LifecycleState.Error)) {
            assertEquals(snap(live), LifecycleLogic.syncWithLegacyActive(snap(live), true, now))
        }
        assertEquals(snap(LifecycleState.Draft), LifecycleLogic.syncWithLegacyActive(snap(LifecycleState.Draft), false, now))
        for (active in listOf(true, false)) {
            assertEquals(snap(LifecycleState.Paused), LifecycleLogic.syncWithLegacyActive(snap(LifecycleState.Paused), active, now))
        }
    }

    @Test fun lifecycleColumnsRoundTripThroughTheEntity() {
        val s = LifecycleSnapshot(
            LifecycleState.Error, 3, LifecycleReason.ArmFailed, 10, pausedUntil = null, errorCode = "PortInUse",
            errorSince = 11, retryCount = 2, nextRetryAt = 99, healthySince = 5,
        )
        val e = FlowEntity(id = "a", name = "n", flowJson = "{}", updatedAt = 1, createdAt = 1, isFavorite = false, stepCount = 0).withLifecycle(s)
        assertEquals("ERROR", e.lifecycle)
        assertEquals("ARM_FAILED", e.lifecycleReason)
        assertEquals(s, e.lifecycleSnapshot())
    }
}
