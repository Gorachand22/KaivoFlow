package com.kaivoflow.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StepAliasesTest {

    private class Conv(
        override val id: String,
        override val from: String,
        override val to: String,
        override val version: Int,
        private val tag: String = "v$version",
    ) : StepConverter {
        override fun convert(params: Map<String, Any?>): ConvertedStep =
            ConvertedStep(
                params = mapOf("mode" to "read", "tag" to tag) + params.filterKeys { it == "keep" },
                legacyParams = params.filterKeys { it != "keep" },
                warnings = if (params.containsKey("odd")) listOf("odd param not mapped") else emptyList(),
            )
    }

    private fun step(type: String, vararg params: Pair<String, Any?>) =
        StepNode(id = "s1", type = type, name = "S", params = persistentMapOf(*params))

    private val live = setOf("device.clipboard", "data.set")

    @Test
    fun `empty map changes nothing and returns the same instance`() {
        val n = StepNormalizer(AliasMap.EMPTY)
        val s = step("anything.at_all")
        assertSame(s, n.normalize(s))
        val flow = Flow(id = "f", name = "F", steps = persistentListOf(s))
        assertSame(flow, n.normalize(flow))
    }

    @Test
    fun `pure rename is applied while loading`() {
        val map = AliasMap.build(mapOf("old.clip" to AliasTarget("device.clipboard")))
        val out = StepNormalizer(map).normalize(step("old.clip", "a" to 1))
        assertEquals("device.clipboard", out.type)
        assertEquals(1, out.params["a"])
        assertNull(out.convertedFrom)
    }

    @Test
    fun `converter is only offered unless autoApply, and records provenance`() {
        val conv = Conv("c.clip", "old.clip_read", "device.clipboard", 1)
        val offered = AliasMap.build(mapOf("old.clip_read" to AliasTarget("device.clipboard", "c.clip")), converters = listOf(conv))
        val s = step("old.clip_read", "keep" to "k", "extra" to "e", "odd" to 1)
        assertSame(s, StepNormalizer(offered).normalize(s))

        val up = StepNormalizer(offered).upgrade(s)!!
        assertEquals("device.clipboard", up.step.type)
        assertEquals("read", up.step.params["mode"])
        assertEquals("k", up.step.params["keep"])
        assertEquals("e", up.step.legacyParams["extra"])
        assertEquals(ConvertedFrom("old.clip_read", "c.clip", 1), up.step.convertedFrom)
        assertEquals(listOf("odd param not mapped"), up.warnings)

        val auto = AliasMap.build(mapOf("old.clip_read" to AliasTarget("device.clipboard", "c.clip", autoApply = true)), converters = listOf(conv))
        assertEquals("device.clipboard", StepNormalizer(auto).normalize(s).type)
    }

    @Test
    fun `conversion is idempotent because the new id is no longer an alias`() {
        val map = AliasMap.build(
            mapOf("old.a" to AliasTarget("data.set", "c", autoApply = true)),
            converters = listOf(Conv("c", "old.a", "data.set", 1)),
        )
        val n = StepNormalizer(map)
        val once = n.normalize(step("old.a", "x" to 1))
        assertSame(once, n.normalize(once))
    }

    @Test
    fun `highest converter version wins and stale steps are detected`() {
        val v1 = Conv("c", "old.a", "data.set", 1)
        val v2 = Conv("c", "old.a", "data.set", 2)
        val mapV1 = AliasMap.build(mapOf("old.a" to AliasTarget("data.set", "c")), converters = listOf(v1))
        val mapV2 = AliasMap.build(mapOf("old.a" to AliasTarget("data.set", "c")), converters = listOf(v1, v2))
        assertEquals(2, mapV2.converter("c")!!.version)
        val converted = StepNormalizer(mapV1).upgrade(step("old.a"))!!.step
        assertEquals("v1", converted.params["tag"])
        assertFalse(StepNormalizer(mapV1).isStale(converted))
        assertTrue(StepNormalizer(mapV2).isStale(converted))
        assertEquals("v2", StepNormalizer(mapV2).upgrade(step("old.a"))!!.step.params["tag"])
    }

    @Test
    fun `two converters with the same id and version are rejected`() {
        try {
            AliasMap.build(converters = listOf(Conv("c", "a", "b", 1), Conv("c", "a", "b", 1)))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `chains are flattened to one hop and cycles are rejected`() {
        val map = AliasMap.build(
            mapOf("a" to AliasTarget("b"), "b" to AliasTarget("data.set", "c")),
            converters = listOf(Conv("c", "b", "data.set", 1)),
        )
        assertEquals("data.set", map.alias("a")!!.newId)
        assertEquals("c", map.alias("a")!!.converterId)
        try {
            AliasMap.build(mapOf("a" to AliasTarget("b"), "b" to AliasTarget("a")))
            fail("expected cycle rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `health check flags alias equal to a live id, missing target and missing converter`() {
        val bad = AliasMap.build(
            mapOf(
                "data.set" to AliasTarget("device.clipboard"),
                "old.x" to AliasTarget("nowhere"),
                "old.y" to AliasTarget("data.set", "missing"),
            ),
            tombstones = listOf(Tombstone("device.clipboard", "gone")),
        )
        val problems = bad.problems(live)
        assertEquals(4, problems.size)
        assertTrue(AliasMap.build(mapOf("old.z" to AliasTarget("data.set"))).problems(live).isEmpty())
    }

    @Test
    fun `tombstoned and unknown ids become placeholders that keep the Step untouched`() {
        val map = AliasMap.build(
            aliases = mapOf("old.merge" to AliasTarget("data.set", "c")),
            tombstones = listOf(Tombstone("old.tcp", "Raw sockets were removed.", replacedBy = null)),
            converters = listOf(Conv("c", "old.merge", "data.set", 1)),
        )
        val n = StepNormalizer(map)
        val known = { id: String -> id in live }
        val tomb = step("old.tcp", "host" to "h")
        val future = step("future.thing", "z" to 9)
        val pending = step("old.merge")

        val a = n.unavailable(tomb, known)!!
        assertEquals(UnavailableReason.Removed, a.reason)
        assertSame(tomb, a.step)
        assertTrue(a.message.contains("Raw sockets"))

        val b = n.unavailable(future, known)!!
        assertEquals(UnavailableReason.Unknown, b.reason)
        assertTrue(b.message.contains("future.thing"))

        assertEquals("data.set", n.unavailable(pending, known)!!.replacedBy)
        assertNull(n.unavailable(step("data.set"), known))

        val flow = Flow(id = "f", name = "F", steps = persistentListOf(tomb, future, step("data.set")))
        assertEquals(2, n.unavailable(flow, known).size)
        assertSame(flow, n.normalize(flow))
    }
}
