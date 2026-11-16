package com.kaivoflow.core.model

/**
 * Identity of one item produced by a Step: output [port] of Step [stepId], position [itemIndex]
 * in that port's list, during execution number [runIndex] of the Step inside the Run.
 *
 * Steps run once per Run today, so [runIndex] is always 0; it is part of the identity so a Loop
 * that re-enters a Step can be added without changing stored data.
 */
data class ItemRef(
    val stepId: String,
    val runIndex: Int,
    val itemIndex: Int,
    val port: Int = 0,
)

/**
 * A failure attached to one item (as opposed to a failure of the whole Step).
 * [stepId] is the Step that produced the failure, [kind] a short machine-readable class such as
 * "item" (one input item could not be processed) or "step" (the whole Step failed), [at] the
 * epoch millisecond when it happened.
 */
data class ItemError(
    val message: String,
    val stepId: String = "",
    val kind: String = "step",
    val at: Long = 0L,
)

/**
 * A single item of data flowing through a Flow.
 *
 * [sources] is the lineage: the items this one was derived from, as seen by the Step that made
 * it. The engine fills it after every Step (Steps may set it themselves when they know better,
 * see FlowRunner), so a panel or an expression can walk from an output item back to the input
 * item(s) behind it. Sourceless Steps (Sparks) produce items with empty [sources].
 *
 * Equality is the data classes' default and therefore includes [sources].
 */
data class Item(
    val json: Map<String, Any?> = emptyMap(),
    /** Named binary attachments (photos, PDFs, audio, downloads) travelling with this item. */
    val binary: Map<String, BinaryDataRef> = emptyMap(),
    /** Set when this item represents a failure that was allowed to continue (onError = Continue). */
    val error: ItemError? = null,
    val sources: List<ItemRef> = emptyList(),
) {
    /**
     * Source-compatibility constructor for code written against the old FlowData shape
     * (`pairedIndex` and a plain error message). New code should use the primary constructor.
     */
    constructor(
        json: Map<String, Any?>,
        pairedIndex: Int?,
        error: String?,
        binary: Map<String, BinaryDataRef>,
    ) : this(
        json = json,
        binary = binary,
        error = error?.let { ItemError(message = it, kind = "legacy") },
        sources = pairedIndex?.let { listOf(ItemRef(stepId = "", runIndex = 0, itemIndex = it)) } ?: emptyList(),
    )

    operator fun get(key: String): Any? = json[key]
    val isError: Boolean get() = error != null
    val hasBinary: Boolean get() = binary.isNotEmpty()

    /** Index of the first source item, kept for readers of the old `pairedIndex` field. */
    val pairedIndex: Int? get() = sources.firstOrNull()?.itemIndex

    fun binaryRef(key: String): BinaryDataRef? = binary[key]

    /** Returns a copy with one attachment added or replaced. */
    fun withBinary(key: String, ref: BinaryDataRef): Item = copy(binary = binary + (key to ref))

    /** Returns a copy with several attachments merged in. */
    fun plusBinary(refs: Map<String, BinaryDataRef>): Item = copy(binary = binary + refs)

    /** Returns a copy with one attachment removed. */
    fun withoutBinary(key: String): Item = copy(binary = binary - key)

    /** Drops every attachment (used when a Run record or export must be byte-free). */
    fun withoutBinary(): Item = if (binary.isEmpty()) this else copy(binary = emptyMap())

    fun withSources(refs: List<ItemRef>): Item = copy(sources = refs)

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): Item = Item(mapOf(*pairs))
    }
}

/** The name every existing Step, test and stored Run uses. Same class as [Item]. */
typealias FlowData = Item

/**
 * Compat readers for stored data. Runs saved before lineage existed hold at most a `pairedIndex`
 * and a plain error string per item; these helpers turn that into the current shape.
 */
object ItemCompat {
    fun fromLegacy(
        json: Map<String, Any?>,
        binary: Map<String, BinaryDataRef> = emptyMap(),
        pairedIndex: Int? = null,
        errorMessage: String? = null,
    ): Item = Item(json, pairedIndex, errorMessage, binary)

    /** Plain message for old readers (and old stored JSON): the message of [Item.error]. */
    fun errorMessage(item: Item): String? = item.error?.message
}
