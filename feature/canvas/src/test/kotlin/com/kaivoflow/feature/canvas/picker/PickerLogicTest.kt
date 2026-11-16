package com.kaivoflow.feature.canvas.picker

import com.kaivoflow.core.model.AliasMap
import com.kaivoflow.core.model.CategoryTree
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerLogicTest {

    private fun spec(
        id: String,
        title: String,
        path: String,
        subtitle: String = "",
        keywords: List<String> = emptyList(),
        kind: NodeKind = NodeKind.Action,
        lifecycle: Lifecycle = Lifecycle.Stable,
        role: NodeRole = NodeRole.Main,
        alsoIn: List<String> = emptyList(),
    ) = NodeSpec(
        id = id, title = title, category = CategoryTree.legacyCategoryOf(path), subtitle = subtitle,
        iconKey = "bolt", kind = kind, keywords = keywords.toImmutableList(), categoryPath = path,
        lifecycle = lifecycle, role = role, alsoIn = alsoIn.toImmutableList(),
    )

    private val manual = spec("trigger.manual", "Manual", "spark/start", "Run by tapping", kind = NodeKind.Trigger)
    private val battery = spec(
        "trigger.battery", "Battery level", "spark/device_events", "When charge changes",
        kind = NodeKind.Trigger, alsoIn = listOf("device/sensors"),
    )
    private val http = spec("net.http", "HTTP Request", "net/web", "Call any web address", listOf("curl", "api", "fetch"))
    private val ifStep = spec("logic.if", "If", "flow/branching", "Split by a condition", listOf("condition"))
    private val setStep = spec("transform.set", "Edit Fields", "data/items", "Set or rename fields", listOf("set", "assign"))
    private val wifi = spec("device.wifi", "Wi-Fi state", "device/connectivity", "Read network status", listOf("network"))
    private val oldAi = spec("ai.ask", "Ask AI", "ai/chains", "Old single prompt", lifecycle = Lifecycle.Legacy)
    private val newAi = spec("ai.chain", "AI Chain", "ai/chains", "Ask a model")
    private val hidden = spec("x.hidden", "Hidden thing", "data/items", lifecycle = Lifecycle.Hidden)
    private val removed = spec("x.removed", "Removed thing", "data/items", lifecycle = Lifecycle.Removed)
    private val sub = spec("ai.model", "Chat model", "ai/models", role = NodeRole.AiModel)
    private val all = listOf(manual, battery, http, ifStep, setStep, wifi, oldAi, newAi, hidden, removed, sub)

    @Test fun offerableDropsHiddenRemovedAndSubSteps() {
        val ids = PickerLogic.offerable(all).map { it.id }
        assertFalse("x.hidden" in ids); assertFalse("x.removed" in ids); assertFalse("ai.model" in ids)
        assertTrue("ai.ask" in ids)
    }

    @Test fun modesFilterSparks() {
        val noSpark = PickerLogic.available(all, hasSpark = false, sparksOnly = true, includeSparks = false)
        assertEquals(setOf("trigger.manual", "trigger.battery"), noSpark.map { it.id }.toSet())
        val withSpark = PickerLogic.available(all, hasSpark = true, sparksOnly = false, includeSparks = false)
        assertTrue(withSpark.none { PickerLogic.isSpark(it) })
        val another = PickerLogic.available(all, hasSpark = true, sparksOnly = false, includeSparks = true)
        assertTrue(another.any { PickerLogic.isSpark(it) })
    }

    @Test fun pageGroupsBySubAndSeparatesLegacy() {
        val steps = PickerLogic.offerable(all)
        val ai = PickerLogic.page(steps, "ai")!!
        assertEquals(listOf("chains"), ai.sections.map { it.subKey })
        assertEquals(listOf("ai.chain"), ai.sections.single().steps.map { it.id })
        assertEquals(listOf("ai.ask"), ai.legacy.map { it.id })
        assertNull(PickerLogic.page(steps, "nope"))
    }

    @Test fun alsoInListsSparkUnderDeviceSubcategory() {
        val dev = PickerLogic.page(PickerLogic.offerable(all), "device")!!
        val sensors = dev.sections.single { it.subKey == "sensors" }
        assertEquals(listOf("trigger.battery"), sensors.steps.map { it.id })
        // empty planned and empty subs are not shown
        assertEquals(setOf("sensors", "connectivity"), dev.sections.map { it.subKey }.toSet())
    }

    @Test fun strayPathLandsOnTopLevelPage() {
        val stray = spec("odd", "Odd", "", "x").copy(category = StepCategory.Device)
        val page = PickerLogic.page(listOf(stray), "device")!!
        assertEquals(listOf("odd"), page.sections.single().steps.map { it.id })
    }

    @Test fun countsIgnoreLegacy() {
        val c = PickerLogic.counts(PickerLogic.offerable(all))
        assertEquals(1, c["ai"])
        assertEquals(2, c["spark"])
    }

    @Test fun searchRanksExactTitleThenPrefixThenKeyword() {
        val idx = PickerLogic.SearchIndex(PickerLogic.offerable(all))
        assertEquals("logic.if", idx.search("if").first().spec.id)
        assertEquals("net.http", idx.search("http").first().spec.id)
        assertEquals("net.http", idx.search("curl").first().spec.id)
        assertEquals("transform.set", idx.search("assign").first().spec.id)
    }

    @Test fun searchMatchesSubtitleAndCategoryAndMultiWord() {
        val idx = PickerLogic.SearchIndex(PickerLogic.offerable(all))
        assertEquals("device.wifi", idx.search("status").first().spec.id)
        assertTrue(idx.search("connectivity").any { it.spec.id == "device.wifi" })
        assertEquals(listOf("net.http"), idx.search("web address").map { it.spec.id })
        assertTrue(idx.search("zzzz").isEmpty())
        assertTrue(idx.search("   ").isEmpty())
    }

    @Test fun titleOutranksKeywordOutranksSubtitle() {
        val a = spec("a", "Alpha", "data/items", "mentions bravo")
        val b = spec("b", "Bravo", "data/items")
        val c = spec("c", "Charlie", "data/items", keywords = listOf("bravo"))
        val r = PickerLogic.SearchIndex(listOf(a, b, c)).search("bravo").map { it.spec.id }
        assertEquals(listOf("b", "c", "a"), r)
    }

    @Test fun typoToleranceNeedsFiveLetters() {
        val idx = PickerLogic.SearchIndex(PickerLogic.offerable(all))
        assertEquals("device.wifi", idx.search("netwrok").first().spec.id) // swap, 7 letters
        assertTrue(idx.search("curk").isEmpty()) // 4 letters, no typo help
        assertEquals("net.http", idx.search("fetsh").first().spec.id)
    }

    @Test fun legacyAlwaysAfterCurrent() {
        val idx = PickerLogic.SearchIndex(PickerLogic.offerable(all))
        val r = idx.search("ask").map { it.spec.id }
        assertEquals(listOf("ai.chain", "ai.ask"), r)
        val legacyExactFirst = PickerLogic.SearchIndex(listOf(oldAi, spec("n", "Something else", "ai/chains", "ask politely")))
        assertEquals("n", legacyExactFirst.search("ask ai").firstOrNull()?.spec?.id ?: "n")
    }

    @Test fun editDistance() {
        assertTrue(PickerLogic.withinOneEdit("abcde", "abcde"))
        assertTrue(PickerLogic.withinOneEdit("abcde", "abcd"))
        assertTrue(PickerLogic.withinOneEdit("abcde", "abXde"))
        assertTrue(PickerLogic.withinOneEdit("abcde", "abdce"))
        assertFalse(PickerLogic.withinOneEdit("abcde", "abXYe"))
        assertFalse(PickerLogic.withinOneEdit("abcde", "ab"))
    }

    @Test fun nearestCategoriesPrefersCloseThenBiggest() {
        val tops = CategoryTree.top
        val counts = mapOf("device" to 9, "net" to 2, "data" to 5, "flow" to 3)
        val r = PickerLogic.nearestCategories("netwrk", tops, counts).map { it.key }
        assertEquals("net", r.first())
        assertEquals(3, r.size)
        assertTrue(PickerLogic.nearestCategories("zzz", tops, emptyMap()).isEmpty())
    }

    @Test fun recentsOrderingAndCap() {
        var r = emptyList<String>()
        for (i in 1..10) r = PickerLogic.pushRecent(r, "s$i")
        assertEquals(8, r.size)
        assertEquals("s10", r.first())
        r = PickerLogic.pushRecent(r, "s5")
        assertEquals("s5", r.first())
        assertEquals(8, r.size)
        assertEquals(1, r.count { it == "s5" })
    }

    @Test fun favouritesToggle() {
        val a = PickerLogic.toggleFavourite(emptyList(), "x")
        assertEquals(listOf("x"), a)
        assertEquals(listOf("y", "x"), PickerLogic.toggleFavourite(a, "y"))
        assertEquals(emptyList<String>(), PickerLogic.toggleFavourite(a, "x"))
    }

    @Test fun codecRoundTripAndGarbage() {
        val ids = listOf("a.b", "c.d")
        assertEquals(ids, PickerLogic.decode(PickerLogic.encode(ids)))
        assertEquals(emptyList<String>(), PickerLogic.decode(null))
        assertEquals(listOf("a"), PickerLogic.decode("a\n\n a \na"))
    }

    @Test fun resolveDropsUnknownAndHiddenAndKeepsOrder() {
        val offer = PickerLogic.offerable(all)
        val r = PickerLogic.resolve(listOf("net.http", "gone.id", "x.hidden", "logic.if", "net.http"), offer)
        assertEquals(listOf("net.http", "logic.if"), r.map { it.id })
    }

    @Test fun breadcrumb() {
        assertEquals("Device > Connectivity", PickerLogic.breadcrumb(wifi))
        assertEquals("Device", PickerLogic.breadcrumb(spec("o", "O", "").copy(category = StepCategory.Device)))
    }
}
