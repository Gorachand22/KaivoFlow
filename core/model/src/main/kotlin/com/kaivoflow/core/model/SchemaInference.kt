package com.kaivoflow.core.model

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

enum class SchemaType { Null, Bool, Number, String, Date, Object, Array, Binary, Mixed }

enum class SchemaOrigin { Observed, Pinned, Declared }

/**
 * The shape of the data seen at one place.
 *
 * Presence: for a field `f` of an object node `o`, `f.of == o.objects` and `f.seenIn` is how many
 * of those objects had the key (a key holding null counts as present). The root node has
 * `of == seenIn == number of items`. For an array element node both are the number of elements read.
 *
 * [mixed] holds every type seen when more than one was seen, including [SchemaType.Null]; [type]
 * is [SchemaType.Mixed] only when two or more non-null types were seen. Fields and element are
 * kept even for a mixed node, so merging stays associative.
 */
data class SchemaNode(
    val type: SchemaType,
    val fields: ImmutableMap<String, SchemaNode> = persistentMapOf(),
    val element: SchemaNode? = null,
    val seenIn: Int = 0,
    val of: Int = 0,
    val sample: String? = null,
    val mixed: ImmutableSet<SchemaType> = persistentSetOf(),
    val origin: SchemaOrigin = SchemaOrigin.Observed,
    /** How many times an object value was observed here. */
    val objects: Int = 0,
    /** True when depth or field caps cut this node short. */
    val truncated: Boolean = false,
) {
    /** True when the field was missing in some of the objects that were seen. */
    val optional: Boolean get() = of > 0 && seenIn < of
    val nullable: Boolean get() = SchemaType.Null in mixed || type == SchemaType.Null
}

data class SchemaLimits(
    val maxItems: Int = 200,
    val maxDepth: Int = 12,
    val maxFields: Int = 500,
    val maxSampleChars: Int = 80,
    val maxArrayElements: Int = 20,
) {
    companion object { val Default = SchemaLimits() }
}

/** A route into a [SchemaNode]: field names, and `[]` for "an element of the array". */
data class SchemaPath(val segments: List<String>) {
    override fun toString(): String = buildString {
        segments.forEachIndexed { i, s -> if (s == ELEMENT) append("[]") else { if (i > 0) append('.'); append(s) } }
    }

    companion object {
        const val ELEMENT = "[]"
        fun of(vararg s: String) = SchemaPath(s.toList())
    }
}

/** A declared (not observed) shape for a Step's output, known before any Run. */
sealed interface SchemaHint {
    data class Static(val node: SchemaNode) : SchemaHint

    /** Computed from the Step's resolved params by a function registered under [id]. */
    data class Computed(val id: String) : SchemaHint
}

object SchemaInference {
    /** Key in the root object that holds the attachments of an item (`$binary.photo`). */
    const val BINARY_KEY = "\$binary"

    fun infer(items: Sequence<Item>, limits: SchemaLimits = SchemaLimits.Default): SchemaNode {
        val ctx = Ctx(limits)
        val root = Acc()
        var n = 0
        for (item in items) {
            if (n >= limits.maxItems) break
            n++
            root.count++
            root.seen(SchemaType.Object)
            root.objects++
            for ((key, value) in item.json) root.field(key, ctx, 1).observe(value, ctx, 1)
            if (item.binary.isNotEmpty()) {
                val bin = root.field(BINARY_KEY, ctx, 1)
                bin.count++
                bin.seen(SchemaType.Object)
                bin.objects++
                for ((name, ref) in item.binary) {
                    val a = bin.field(name, ctx, 2)
                    a.count++
                    a.seen(SchemaType.Binary)
                    if (a.sample == null) a.sample = clip("${ref.fileName} (${ref.mimeType})", limits)
                }
            }
        }
        return root.toNode(of = n, limits = limits)
    }

    /** Combines two nodes. Associative: counts add, type sets union, fields and element merge. */
    fun merge(a: SchemaNode, b: SchemaNode): SchemaNode {
        val seen = LinkedHashSet<SchemaType>()
        seen += seenTypes(a); seen += seenTypes(b)
        if (SchemaType.Date in seen && SchemaType.String in seen) seen -= SchemaType.Date
        val objects = a.objects + b.objects
        val fields = LinkedHashMap<String, SchemaNode>()
        for (k in a.fields.keys + b.fields.keys) {
            val fa = a.fields[k]
            val fb = b.fields[k]
            val m = when {
                fa == null -> fb!!
                fb == null -> fa
                else -> merge(fa, fb)
            }
            fields[k] = m.copy(of = objects)
        }
        val element = when {
            a.element == null -> b.element
            b.element == null -> a.element
            else -> merge(a.element, b.element)
        }
        return SchemaNode(
            type = typeOf(seen),
            fields = fields.toImmutableMap(),
            element = element,
            seenIn = a.seenIn + b.seenIn,
            of = a.of + b.of,
            sample = a.sample ?: b.sample,
            mixed = if (seen.size > 1) seen.toImmutableSet() else persistentSetOf(),
            origin = if (a.origin == b.origin) a.origin else SchemaOrigin.Observed,
            objects = objects,
            truncated = a.truncated || b.truncated,
        )
    }

    /** A static declared node, marked [SchemaOrigin.Declared]. */
    fun fromDeclared(hint: SchemaHint.Static): SchemaNode = hint.node.copy(origin = SchemaOrigin.Declared)

    /** Computed hints go through a registry of functions keyed by id (never lambdas in data). */
    fun fromDeclared(
        hint: SchemaHint,
        registry: Map<String, (Map<String, Any?>) -> SchemaNode>,
        params: Map<String, Any?>,
    ): SchemaNode? = when (hint) {
        is SchemaHint.Static -> fromDeclared(hint)
        is SchemaHint.Computed -> registry[hint.id]?.invoke(params)?.copy(origin = SchemaOrigin.Declared)
    }

    /** Every path under [node] (not the root itself), depth first, fields in order, arrays as `[]`. */
    fun pathsOf(node: SchemaNode, maxDepth: Int = 6): List<SchemaPath> {
        val out = ArrayList<SchemaPath>()
        fun walk(n: SchemaNode, at: List<String>, depth: Int) {
            if (depth >= maxDepth) return
            for ((k, f) in n.fields) {
                val p = at + k
                out += SchemaPath(p)
                walk(f, p, depth + 1)
            }
            n.element?.let { e ->
                val p = at + SchemaPath.ELEMENT
                out += SchemaPath(p)
                walk(e, p, depth + 1)
            }
        }
        walk(node, emptyList(), 0)
        return out
    }

    fun nodeAt(node: SchemaNode, path: SchemaPath): SchemaNode? {
        var cur = node
        for (s in path.segments) {
            cur = if (s == SchemaPath.ELEMENT) cur.element ?: return null else cur.fields[s] ?: return null
        }
        return cur
    }

    fun typeAt(node: SchemaNode, path: SchemaPath): SchemaType? = nodeAt(node, path)?.type

    // ---- internals ----

    private fun seenTypes(n: SchemaNode): Set<SchemaType> = when {
        n.mixed.isNotEmpty() -> n.mixed
        n.type == SchemaType.Mixed -> persistentSetOf()
        else -> persistentSetOf(n.type)
    }

    private fun typeOf(seen: Set<SchemaType>): SchemaType {
        val nonNull = seen - SchemaType.Null
        return when {
            nonNull.isEmpty() -> SchemaType.Null
            nonNull.size == 1 -> nonNull.first()
            else -> SchemaType.Mixed
        }
    }

    private fun clip(s: kotlin.String, limits: SchemaLimits): kotlin.String =
        if (s.length <= limits.maxSampleChars) s else s.take(limits.maxSampleChars - 1) + "…"

    fun looksLikeIsoDate(s: kotlin.String): Boolean {
        if (s.length < 10 || s.length > 40 || !s[0].isDigit() || s[4] != '-') return false
        val t = s.trim()
        return runCatching { OffsetDateTime.parse(t) }.isSuccess ||
            runCatching { ZonedDateTime.parse(t) }.isSuccess ||
            runCatching { Instant.parse(t) }.isSuccess ||
            runCatching { LocalDateTime.parse(t) }.isSuccess ||
            (t.length == 10 && runCatching { LocalDate.parse(t) }.isSuccess)
    }

    private class Ctx(val limits: SchemaLimits) {
        var fieldCount = 0
    }

    /** Mutable accumulator for one place in the shape; turned into an immutable node at the end. */
    private class Acc {
        var count = 0
        val types = LinkedHashSet<SchemaType>()
        var objects = 0
        var sample: kotlin.String? = null
        var truncated = false
        val fields = LinkedHashMap<kotlin.String, Acc>()
        var element: Acc? = null

        fun seen(t: SchemaType) { types += t }

        fun field(name: kotlin.String, ctx: Ctx, depth: Int): Acc {
            fields[name]?.let { return it }
            val a = Acc()
            if (ctx.fieldCount >= ctx.limits.maxFields) { truncated = true; return Acc() }
            ctx.fieldCount++
            fields[name] = a
            return a
        }

        /** Records one observed [value] at this place; [depth] is the depth of this place. */
        fun observe(value: Any?, ctx: Ctx, depth: Int) {
            count++
            when (value) {
                null -> seen(SchemaType.Null)
                is Boolean -> { seen(SchemaType.Bool); if (sample == null) sample = value.toString() }
                is Number -> { seen(SchemaType.Number); if (sample == null) sample = Values0.num(value) }
                is kotlin.String -> {
                    seen(if (looksLikeIsoDate(value)) SchemaType.Date else SchemaType.String)
                    if (sample == null) sample = clip(value, ctx.limits)
                }
                is Map<*, *> -> {
                    seen(SchemaType.Object)
                    objects++
                    if (depth >= ctx.limits.maxDepth) { truncated = true; return }
                    for ((k, v) in value) field(k.toString(), ctx, depth + 1).observe(v, ctx, depth + 1)
                }
                is Iterable<*> -> {
                    seen(SchemaType.Array)
                    if (depth >= ctx.limits.maxDepth) { truncated = true; return }
                    val el = element ?: Acc().also { element = it }
                    var n = 0
                    for (v in value) {
                        if (n >= ctx.limits.maxArrayElements) { truncated = true; break }
                        n++
                        el.observe(v, ctx, depth + 1)
                    }
                }
                else -> { seen(SchemaType.String); if (sample == null) sample = clip(value.toString(), ctx.limits) }
            }
        }

        /** [of] is the presence base for this node (parent's object count, or the item count at the root). */
        fun toNode(of: Int, limits: SchemaLimits): SchemaNode {
            val seen = LinkedHashSet(types)
            if (SchemaType.Date in seen && SchemaType.String in seen) seen -= SchemaType.Date
            val kids = LinkedHashMap<kotlin.String, SchemaNode>()
            for ((k, a) in fields) kids[k] = a.toNode(objects, limits)
            val el = element?.let { it.toNode(it.count, limits) }
            return SchemaNode(
                type = typeOf(seen),
                fields = kids.toImmutableMap(),
                element = el,
                seenIn = count,
                of = of,
                sample = sample,
                mixed = if (seen.size > 1) seen.toImmutableSet() else persistentSetOf(),
                objects = objects,
                truncated = truncated,
            )
        }
    }

    private object Values0 {
        fun num(n: Number): kotlin.String =
            if (n is Double && n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
    }
}
