package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineNeedsTest {

    private fun flow(id: String, vararg types: String, disabled: Set<String> = emptySet()) = Flow(
        id = id, name = id,
        steps = persistentListOf(*types.mapIndexed { i, t -> StepNode("$id-$i", t, "Step $i", disabled = "$id-$i" in disabled) }.toTypedArray()),
    )

    private fun ef(f: Flow, s: LifecycleState = LifecycleState.Published) = EngineFlow(f, s)

    @Test fun `no flows means no engine`() {
        val r = EngineNeeds.compute(emptyList())
        assertFalse(r.engineNeeded); assertFalse(r.systemWakeupsOnly); assertEquals(0, r.liveFlowCount)
    }

    @Test fun `classification of every spark type follows spec 3-3`() {
        val expected = mapOf(
            SparkTypes.MANUAL to EngineNeed.None, SparkTypes.SCHEDULE to EngineNeed.None, SparkTypes.WEBHOOK to EngineNeed.Required,
            SparkTypes.NOTIFICATION to EngineNeed.Wanted, SparkTypes.SMS to EngineNeed.None, SparkTypes.SHARE to EngineNeed.None,
            SparkTypes.BATTERY to EngineNeed.Wanted, SparkTypes.GEOFENCE to EngineNeed.None, SparkTypes.BLUETOOTH to EngineNeed.Optional,
            SparkTypes.WIFI to EngineNeed.Wanted, SparkTypes.HEADSET to EngineNeed.Required, SparkTypes.BOOT to EngineNeed.None,
            SparkTypes.SCREEN to EngineNeed.Required, SparkTypes.POWER to EngineNeed.Required,
        )
        expected.forEach { (type, need) -> assertEquals(type, need, SparkTypes.need(type)) }
        SparkTypes.all.forEach { assertTrue("$it classified", it in expected) }
        assertEquals(EngineNeed.None, SparkTypes.need("trigger.unknown"))
        assertEquals(EngineNeed.None, SparkTypes.need("net.http"))
    }

    @Test fun `required and wanted sparks start the engine in Published and Error`() {
        for (type in listOf(SparkTypes.WEBHOOK, SparkTypes.HEADSET, SparkTypes.NOTIFICATION)) {
            for (state in listOf(LifecycleState.Published, LifecycleState.Error)) {
                val r = EngineNeeds.compute(listOf(ef(flow("a", type), state)))
                assertTrue("$type $state", r.engineNeeded)
                assertEquals(type, r.reasons.single().sparkType)
            }
        }
    }

    @Test fun `battery and wifi sparks make the engine wanted because the engine sources them`() {
        for (type in listOf(SparkTypes.BATTERY, SparkTypes.WIFI)) {
            val r = EngineNeeds.compute(listOf(ef(flow("a", type))))
            assertTrue(type, r.engineNeeded)
            assertEquals(EngineNeed.Wanted, r.reasons.single().need)
        }
    }

    @Test fun `draft and paused flows never need the engine`() {
        for (state in listOf(LifecycleState.Draft, LifecycleState.Paused)) {
            val r = EngineNeeds.compute(listOf(ef(flow("a", SparkTypes.WEBHOOK), state)))
            assertFalse(state.name, r.engineNeeded)
            assertEquals(0, r.liveFlowCount)
        }
    }

    @Test fun `system wake-up only flows keep the engine off and say so`() {
        val r = EngineNeeds.compute(listOf(
            ef(flow("a", SparkTypes.SCHEDULE)), ef(flow("b", SparkTypes.SMS)), ef(flow("c", SparkTypes.GEOFENCE)),
            ef(flow("d", SparkTypes.SHARE)), ef(flow("e", SparkTypes.BOOT)), ef(flow("f", SparkTypes.BLUETOOTH)),
        ))
        assertFalse(r.engineNeeded); assertTrue(r.systemWakeupsOnly); assertEquals(6, r.liveFlowCount)
    }

    @Test fun `disabled sparks and non-spark steps are ignored`() {
        val f = flow("a", SparkTypes.WEBHOOK, "net.http", disabled = setOf("a-0"))
        assertFalse(EngineNeeds.compute(listOf(ef(f))).engineNeeded)
        assertFalse(EngineNeeds.compute(listOf(ef(flow("b", "net.http")))).engineNeeded)
    }

    @Test fun `always-on mode forces the engine`() {
        val r = EngineNeeds.compute(emptyList(), alwaysOnMode = true)
        assertTrue(r.engineNeeded); assertTrue(r.reasons.isEmpty()); assertFalse(r.systemWakeupsOnly)
        assertTrue(EngineNeeds.compute(listOf(ef(flow("a", SparkTypes.SCHEDULE))), alwaysOnMode = true).engineNeeded)
    }

    @Test fun `reasons name every flow and spark`() {
        val r = EngineNeeds.compute(listOf(ef(flow("a", SparkTypes.WEBHOOK, SparkTypes.NOTIFICATION)), ef(flow("b", SparkTypes.HEADSET))))
        assertEquals(listOf("a-0", "a-1", "b-0"), r.reasons.map { it.stepId })
        assertEquals(EngineNeed.Wanted, r.reasons[1].need)
    }
}
