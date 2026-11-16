package com.kaivoflow.feature.canvas.picker

import com.kaivoflow.core.model.AliasMap
import com.kaivoflow.core.model.CategoryTree
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.TopCategory

/**
 * Pure logic behind the Step Picker (taxonomy spec section 7). No Android, no Compose, so it is
 * covered by JVM tests. The composable only renders what these functions return.
 */

/** One ranked search result. [score] is for tests and tie-breaks; higher is better. */
data class SearchHit(val spec: NodeSpec, val score: Int)

/** A subcategory with the Steps shown under it. */
data class PickerSection(val topKey: String, val subKey: String, val label: String, val steps: List<NodeSpec>)

/** A category page: normal sections plus Steps with the Legacy lifecycle (shown collapsed). */
data class CategoryPage(val top: TopCategory, val sections: List<PickerSection>, val legacy: List<NodeSpec>)

/** Icon key (see KaivoIcons) for each top level category. */
object CategoryIcons {
    private val byTop = mapOf(
        "spark" to "bolt",
        "flow" to "flow",
        "data" to "dataObject",
        "files" to "file",
        "net" to "globe",
        "app" to "plug",
        "ai" to "sparkles",
        "code" to "code",
        "security" to "shield",
        "device" to "phone",
    )

    fun of(topKey: String): String = byTop[topKey] ?: "grid"
}

object PickerLogic {

    const val MAX_RECENT = 8
    const val SPARK = "spark"

    /** Steps the picker may offer: main-role only (sub-Steps belong to agent slots), never Hidden or Removed. */
    fun offerable(specs: List<NodeSpec>): List<NodeSpec> =
        specs.filter {
            it.role == NodeRole.Main && it.lifecycle != Lifecycle.Hidden && it.lifecycle != Lifecycle.Removed
        }

    fun isSpark(spec: NodeSpec): Boolean = spec.isTrigger || spec.categoryPath.startsWith("$SPARK/")

    /** Top-level key of a spec; unassigned paths fall back to the legacy category's nearest top level. */
    fun topKeyOf(spec: NodeSpec): String {
        CategoryTree.topOf(spec.categoryPath)?.let { return it.key }
        return when (spec.category) {
            com.kaivoflow.core.model.StepCategory.Trigger -> "spark"
            com.kaivoflow.core.model.StepCategory.Ai -> "ai"
            com.kaivoflow.core.model.StepCategory.Saas -> "app"
            com.kaivoflow.core.model.StepCategory.Logic -> "flow"
            com.kaivoflow.core.model.StepCategory.Transform -> "data"
            com.kaivoflow.core.model.StepCategory.Device -> "device"
        }
    }

    /** Steps that count as the user's choices in the current mode. */
    fun available(specs: List<NodeSpec>, hasSpark: Boolean, sparksOnly: Boolean, includeSparks: Boolean): List<NodeSpec> {
        val base = offerable(specs)
        return when {
            sparksOnly -> base.filter(::isSpark)
            hasSpark && !includeSparks -> base.filterNot(::isSpark)
            else -> base
        }
    }

    /** Count of non-legacy Steps per top level key (primary placement only). */
    fun counts(steps: List<NodeSpec>): Map<String, Int> =
        steps.filter { it.lifecycle != Lifecycle.Legacy }.groupingBy(::topKeyOf).eachCount()

    /** Top levels that have at least one visible Step (legacy-only categories are kept too), in tree order. */
    fun visibleTops(steps: List<NodeSpec>): List<TopCategory> {
        val present = steps.map(::topKeyOf).toSet()
        return CategoryTree.top.filter { it.key in present }
    }

    /**
     * One category page. Cross-listed Steps (alsoIn) appear under the other subcategory too. Empty
     * subcategories are dropped, including planned ones. Legacy Steps go to [CategoryPage.legacy].
     */
    fun page(steps: List<NodeSpec>, topKey: String): CategoryPage? {
        val top = CategoryTree.top.firstOrNull { it.key == topKey } ?: return null
        val sections = mutableListOf<PickerSection>()
        for (sub in top.subs) {
            val path = CategoryTree.path(top.key, sub.key)
            val inSub = steps
                .filter { it.lifecycle != Lifecycle.Legacy && (it.categoryPath == path || path in it.alsoIn) }
                .sortedBy { it.title.lowercase() }
            if (inSub.isNotEmpty()) sections += PickerSection(top.key, sub.key, sub.label, inSub)
        }
        // Steps whose path is not in the tree still land on their top level's page, never lost.
        val placed = sections.flatMap { s -> s.steps.map { it.id } }.toSet()
        val strays = steps.filter {
            it.lifecycle != Lifecycle.Legacy && topKeyOf(it) == top.key && it.id !in placed
        }.sortedBy { it.title.lowercase() }
        if (strays.isNotEmpty()) sections += PickerSection(top.key, "", top.label, strays)
        val legacy = steps.filter { it.lifecycle == Lifecycle.Legacy && topKeyOf(it) == top.key }
            .sortedBy { it.title.lowercase() }
        return CategoryPage(top, sections, legacy)
    }

    /** Breadcrumb for a spec, "Device > Sensors"; falls back to the top level label. */
    fun breadcrumb(spec: NodeSpec): String =
        if (CategoryTree.exists(spec.categoryPath)) CategoryTree.breadcrumb(spec.categoryPath)
        else CategoryTree.top.firstOrNull { it.key == topKeyOf(spec) }?.label ?: ""

    // ---------------------------------------------------------------- search

    private val splitter = Regex("[^a-z0-9]+")

    internal fun words(text: String): List<String> =
        text.lowercase().split(splitter).filter { it.isNotEmpty() }

    /** True when [a] and [b] differ by at most one insertion, deletion, substitution or adjacent swap. */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        if (la == lb) {
            var first = -1
            for (i in 0 until la) {
                if (a[i] != b[i]) {
                    if (first == -1) first = i else {
                        // second mismatch: only valid as an adjacent swap
                        return i == first + 1 && a[first] == b[i] && a[i] == b[first] &&
                            (i + 1 until la).all { a[it] == b[it] }
                    }
                }
            }
            return true
        }
        val (s, l) = if (la < lb) a to b else b to a
        var i = 0
        var j = 0
        var skipped = false
        while (i < s.length && j < l.length) {
            if (s[i] == l[j]) { i++; j++ } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }

    private class Entry(val spec: NodeSpec) {
        val titleLower = spec.title.lowercase().trim()
        val titleWords = words(spec.title)
        val keywordsLower = spec.keywords.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        val keywordWords = keywordsLower.flatMap { words(it) }
        val subtitleWords = words(spec.subtitle)
        val categoryWords: List<String> = run {
            val paths = listOf(spec.categoryPath) + spec.alsoIn
            val labels = paths.flatMap { p ->
                listOfNotNull(CategoryTree.topOf(p)?.label, CategoryTree.subOf(p)?.label)
            }
            labels.flatMap { words(it) }.distinct()
        }
    }

    /**
     * Search index built once from the specs (title, keywords and aliases, subtitle, category names).
     * Ranking, best first: exact title, title prefix, title word, keyword exact, keyword prefix,
     * subtitle word, category name, then typo matches (one edit, words of 5+ letters). Several
     * query words must all match. Legacy Steps sort after every current Step.
     */
    class SearchIndex(specs: List<NodeSpec>) {
        private val entries = specs.map { Entry(it) }

        fun search(query: String): List<SearchHit> {
            val tokens = words(query)
            if (tokens.isEmpty()) return emptyList()
            val phrase = tokens.joinToString(" ")
            val hits = ArrayList<SearchHit>()
            for (e in entries) {
                var total = 0
                var ok = true
                for (t in tokens) {
                    val s = tokenScore(t, e)
                    if (s == 0) { ok = false; break }
                    total += s
                }
                if (!ok) continue
                var score = total / tokens.size
                val title = e.titleWords.joinToString(" ")
                if (title == phrase) score = EXACT_TITLE
                else if (title.startsWith(phrase)) score = maxOf(score, TITLE_PREFIX)
                hits += SearchHit(e.spec, score)
            }
            return hits.sortedWith(
                compareBy<SearchHit> { it.spec.lifecycle == Lifecycle.Legacy }
                    .thenByDescending { it.score }
                    .thenBy { it.spec.title.lowercase() }
                    .thenBy { it.spec.id },
            )
        }

        private fun tokenScore(t: String, e: Entry): Int {
            var best = 0
            fun up(v: Int) { if (v > best) best = v }
            if (e.titleLower == t) up(EXACT_TITLE)
            if (e.titleLower.startsWith(t)) up(TITLE_PREFIX)
            if (e.titleWords.any { it == t }) up(85)
            if (e.titleWords.any { it.startsWith(t) }) up(80)
            if (t.length >= 3 && e.titleLower.contains(t)) up(55)
            if (e.keywordsLower.any { it == t }) up(70)
            if (e.keywordWords.any { it == t }) up(65)
            if (e.keywordWords.any { it.startsWith(t) }) up(60)
            if (t.length >= 3 && e.keywordsLower.any { it.contains(t) }) up(45)
            if (e.subtitleWords.any { it == t }) up(42)
            if (e.subtitleWords.any { it.startsWith(t) }) up(38)
            if (e.categoryWords.any { it == t }) up(32)
            if (e.categoryWords.any { it.startsWith(t) }) up(30)
            if (best == 0 && t.length >= 5) {
                if (e.titleWords.any { it.length >= 5 && withinOneEdit(t, it) }) up(28)
                else if (e.keywordWords.any { it.length >= 5 && withinOneEdit(t, it) }) up(25)
            }
            return best
        }

        companion object {
            const val EXACT_TITLE = 100
            const val TITLE_PREFIX = 90
        }
    }

    /**
     * Up to three top levels to suggest when nothing matches: the labels closest to the query
     * (shared prefix, one edit), padded with the largest categories.
     */
    fun nearestCategories(query: String, tops: List<TopCategory>, counts: Map<String, Int>): List<TopCategory> {
        val tokens = words(query)
        fun closeness(t: TopCategory): Int {
            val lw = words(t.label) + t.subs.flatMap { words(it.label) }
            var best = 0
            for (q in tokens) for (w in lw) {
                var common = 0
                while (common < q.length && common < w.length && q[common] == w[common]) common++
                var s = common
                if (q.length >= 4 && w.length >= 4 && withinOneEdit(q, w)) s = maxOf(s, 5)
                if (s > best) best = s
            }
            return best
        }
        return tops.filter { (counts[it.key] ?: 0) > 0 }
            .sortedWith(compareByDescending<TopCategory> { closeness(it) }.thenByDescending { counts[it.key] ?: 0 })
            .take(3)
    }

    // ---------------------------------------------------- recents / favourites

    /** Most recent first, no duplicates, capped at [max]. */
    fun pushRecent(current: List<String>, id: String, max: Int = MAX_RECENT): List<String> =
        (listOf(id) + current.filter { it != id }).take(max)

    fun toggleFavourite(current: List<String>, id: String): List<String> =
        if (id in current) current.filter { it != id } else listOf(id) + current

    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(raw: String?): List<String> =
        raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /**
     * Turns stored ids into live specs: follows renames through [aliases], drops ids that no longer
     * resolve to an offerable Step, removes duplicates and keeps stored order.
     */
    fun resolve(ids: List<String>, offerable: Collection<NodeSpec>, aliases: AliasMap = AliasMap.EMPTY): List<NodeSpec> {
        val byId = offerable.associateBy { it.id }
        val out = LinkedHashMap<String, NodeSpec>()
        for (raw in ids) {
            val id = aliases.alias(raw)?.newId ?: raw
            val spec = byId[id] ?: continue
            out.putIfAbsent(spec.id, spec)
        }
        return out.values.toList()
    }
}
