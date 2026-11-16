package com.kaivoflow.core.model

import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/**
 * Compatibility for saved Flows (taxonomy spec section 6). Pure data and pure functions, no IO.
 *
 * Old Step ids are never dropped silently: an id is either an alias (renamed, or converted to a
 * current Step), a tombstone (removed on purpose), or unknown (for example written by a newer app).
 * Tombstoned and unknown Steps load as "Unavailable Step" placeholders that keep id, params and wiring.
 */

/** Where an old id points. [converterId] is null for a pure rename. */
data class AliasTarget(
    val newId: String,
    val converterId: String? = null,
    /** Apply the converter while loading. False means it is only offered through a manual upgrade. */
    val autoApply: Boolean = false,
)

/** A Step type that was removed on purpose. [replacedBy] is a hint for the message, not a mapping. */
data class Tombstone(val id: String, val reason: String, val replacedBy: String? = null)

/** Result of a converter run. [warnings] are shown once to the owner, never silent. */
data class ConvertedStep(
    val params: Map<String, Any?>,
    val legacyParams: Map<String, Any?> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

/**
 * Converts the params of [from] into params for [to]. Must be pure, deterministic, idempotent and
 * free of IO. A fixed converter ships as a higher [version]; the highest version of an id wins.
 */
interface StepConverter {
    val id: String
    val from: String
    val to: String
    val version: Int
    fun convert(params: Map<String, Any?>): ConvertedStep
}

/** Outcome of upgrading one Step. */
data class StepUpgrade(val step: StepNode, val warnings: List<String>)

class AliasMap private constructor(
    private val aliases: Map<String, AliasTarget>,
    private val tombstones: Map<String, Tombstone>,
    private val converters: Map<String, StepConverter>,
) {
    val aliasIds: Set<String> get() = aliases.keys
    val tombstoneIds: Set<String> get() = tombstones.keys

    fun alias(oldId: String): AliasTarget? = aliases[oldId]

    fun tombstone(id: String): Tombstone? = tombstones[id]

    fun converter(id: String): StepConverter? = converters[id]

    val isEmpty: Boolean get() = aliases.isEmpty() && tombstones.isEmpty()

    /**
     * Problems with this map against the live registry: an alias that equals a live id, an alias or
     * tombstone that overlaps another, a target that is missing, or a converter that is missing.
     * Empty list means healthy.
     */
    fun problems(liveIds: Set<String>): List<String> {
        val out = mutableListOf<String>()
        aliases.forEach { (old, target) ->
            if (old in liveIds) out += "alias \"$old\" equals a live id"
            if (target.newId !in liveIds) out += "alias \"$old\" points to missing id \"${target.newId}\""
            val cid = target.converterId
            if (cid != null && cid !in converters) out += "alias \"$old\" names missing converter \"$cid\""
        }
        tombstones.keys.forEach { id ->
            if (id in liveIds) out += "tombstone \"$id\" equals a live id"
            if (id in aliases) out += "id \"$id\" is both an alias and a tombstone"
        }
        return out
    }

    companion object {
        val EMPTY = AliasMap(emptyMap(), emptyMap(), emptyMap())

        /**
         * Builds a map and flattens chains (old to current) so lookup is one hop. A chain keeps the
         * converter of its last hop that has one. Throws on a cycle or on a duplicated converter version.
         */
        fun build(
            aliases: Map<String, AliasTarget> = emptyMap(),
            tombstones: List<Tombstone> = emptyList(),
            converters: List<StepConverter> = emptyList(),
        ): AliasMap {
            val byConverter = HashMap<String, StepConverter>()
            converters.forEach { c ->
                val prior = byConverter[c.id]
                require(prior == null || prior.version != c.version) {
                    "converter \"${c.id}\" has two implementations of version ${c.version}"
                }
                if (prior == null || c.version > prior.version) byConverter[c.id] = c
            }
            val flat = LinkedHashMap<String, AliasTarget>()
            aliases.keys.sorted().forEach { start ->
                var current = aliases.getValue(start)
                var converterId = current.converterId
                var auto = current.autoApply
                val seen = hashSetOf(start)
                while (true) {
                    val next = aliases[current.newId] ?: break
                    require(seen.add(current.newId)) { "alias cycle through \"${current.newId}\"" }
                    if (next.converterId != null) {
                        converterId = next.converterId
                        auto = next.autoApply
                    }
                    current = next
                }
                flat[start] = AliasTarget(current.newId, converterId, auto)
            }
            return AliasMap(flat, tombstones.associateBy { it.id }, byConverter)
        }
    }
}

/** Why a Step cannot run on this build. */
enum class UnavailableReason { Removed, Unknown }

/**
 * Placeholder view of a Step this build cannot run. It wraps the untouched [step], so saving the Flow
 * writes the same type, params and wiring back: nothing is mutated or dropped.
 */
data class UnavailableStep(
    val step: StepNode,
    val reason: UnavailableReason,
    /** Plain message for validation and the inspector. */
    val message: String,
    val replacedBy: String? = null,
)

/**
 * Applies an [AliasMap] to Steps and Flows. Load-time normalisation only renames (and runs converters
 * marked autoApply); merges that change behaviour are offered through [upgrade] instead.
 */
class StepNormalizer(val map: AliasMap = AliasMap.EMPTY) {

    /** Pure renames and auto-applied converters. Steps with no matching alias are returned as the same instance. */
    fun normalize(step: StepNode): StepNode {
        val target = map.alias(step.type) ?: return step
        if (target.converterId == null) return step.copy(type = target.newId)
        return if (target.autoApply) upgrade(step)?.step ?: step else step
    }

    fun normalize(flow: Flow): Flow {
        if (map.isEmpty) return flow
        var changed = false
        val steps = flow.steps.map { s -> normalize(s).also { if (it !== s) changed = true } }
        return if (changed) flow.copy(steps = steps.toImmutableList()) else flow
    }

    /** Runs the alias converter for [step]; null when its type is not an alias source or the converter is missing. */
    fun upgrade(step: StepNode): StepUpgrade? {
        val target = map.alias(step.type) ?: return null
        val converterId = target.converterId
        if (converterId == null) return StepUpgrade(step.copy(type = target.newId), emptyList())
        val converter = map.converter(converterId) ?: return null
        val result = converter.convert(step.params)
        return StepUpgrade(
            step.copy(
                type = target.newId,
                params = result.params.toImmutableMap(),
                legacyParams = (step.legacyParams + result.legacyParams).toImmutableMap(),
                convertedFrom = ConvertedFrom(step.type, converter.id, converter.version),
            ),
            result.warnings,
        )
    }

    /** True when a newer version of the converter that produced [step] exists. */
    fun isStale(step: StepNode): Boolean {
        val from = step.convertedFrom ?: return false
        val current = map.converter(from.converterId) ?: return false
        return current.version > from.version
    }

    /** Placeholder for a Step whose type is not runnable here, or null when [isKnown] accepts it. */
    fun unavailable(step: StepNode, isKnown: (String) -> Boolean): UnavailableStep? {
        if (isKnown(step.type)) return null
        val tomb = map.tombstone(step.type)
        if (tomb != null) {
            val hint = tomb.replacedBy?.let { " Use $it instead." }.orEmpty()
            return UnavailableStep(step, UnavailableReason.Removed, "${tomb.reason}$hint", tomb.replacedBy)
        }
        val target = map.alias(step.type)
        if (target != null) {
            return UnavailableStep(
                step,
                UnavailableReason.Removed,
                "This Step was replaced by ${target.newId}. Upgrade it to keep using it.",
                target.newId,
            )
        }
        return UnavailableStep(
            step,
            UnavailableReason.Unknown,
            "This Step type (${step.type}) is not available on this build. Remove it or update the app.",
        )
    }

    fun unavailable(flow: Flow, isKnown: (String) -> Boolean): List<UnavailableStep> =
        flow.steps.mapNotNull { unavailable(it, isKnown) }
}
