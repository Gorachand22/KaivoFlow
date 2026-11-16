package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.CryptoMath
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.delegate
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import java.time.Instant
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Remove Duplicates. Memory is per Run, per day, or across Runs. "Across Runs" and "today" keep
 * their seen set in the device file store under a name derived from the Flow and Step id (so two
 * Flows never share it; per-Flow static data does not exist yet, see the 2C implementation notes).
 * Absorbs the old "Only new items" poll Step through `source = url`, which first fetches a feed and
 * then keeps only unseen entries.
 */
class DedupeNode(private val devices: DeviceGateway = NoopDeviceGateway) : StepExecutor {

    private val poll = PollChangesNode(devices)

    override val spec = NodeSpec(
        id = "data.dedupe",
        title = "Remove duplicates",
        category = StepCategory.Logic,
        subtitle = "Drop repeated items, keeping the first",
        iconKey = "unique",
        params = (persistentListOf(
            ParamSpec("fields", "Compare using fields", ParamType.Text, placeholder = "id, email (leave empty for the whole item)"),
            ParamSpec(
                key = "scope",
                label = "Remember",
                type = ParamType.Select,
                default = "run",
                options = persistentListOf(
                    ParamOption("run", "Within this Run"),
                    ParamOption("day", "Within today"),
                    ParamOption("always", "Across every Run"),
                ),
            ),
            ParamSpec(
                "source", "Items come from", ParamType.Select, default = "input",
                options = selectOptions("input" to "The previous Step", "url" to "A web feed (fetch, then keep only new)"),
            ),
        ) + pollParams()).toImmutableList(),
        keywords = persistentListOf("unique", "distinct", "duplicate", "poll", "new", "changed", "seen", "only new"),
        replaces = persistentListOf("logic.poll"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val raw = ctx.step.params
        if (Values.asString(raw["source"], "input") == "url") {
            val pollParams = raw.filterKeys { it != "source" && it != "fields" && it != "scope" }.toMutableMap()
            if (Values.asString(raw["stateKey"]).isBlank()) pollParams["stateKey"] = stateName(ctx)
            return ctx.delegate(poll, pollParams)
        }
        val params = ctx.params()
        val fields = Values.asString(params["fields"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val scope = Values.asString(params["scope"], "run")
        val seen = LinkedHashSet<String>()
        val persisted = scope == "always" || scope == "day"
        val stateKey = ctx.resolveText(raw["stateKey"], 0).ifBlank { stateName(ctx) }
        val today = Instant.ofEpochMilli(ctx.clock()).atZone(runCatching { ZoneId.of(ctx.timezone) }.getOrDefault(ZoneId.of("UTC"))).toLocalDate().toString()
        if (persisted && !Values.asBoolean(raw["reset"] ?: false)) {
            val lines = devices.readFile(stateKey).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val header = lines.firstOrNull()?.takeIf { it.startsWith("#day=") }
            val fresh = scope == "always" || header == "#day=$today"
            if (fresh) lines.filterNot { it.startsWith("#") }.forEach { seen += it }
        }
        val before = seen.size
        val kept = ctx.input.filter { item ->
            val key = if (fields.isEmpty()) Values.stringify(item.json)
            else fields.joinToString("|") { Values.asString(Values.deepGet(item.json, it)) }
            seen.add(if (persisted) CryptoMath.hashHex("SHA-256", key) else key)
        }
        if (persisted) {
            val body = seen.toList().takeLast(MAX_REMEMBERED).joinToString("\n")
            devices.writeFile(stateKey, if (scope == "day") "#day=$today\n$body" else body)
        }
        ctx.log("Removed ${ctx.input.size - kept.size} duplicate items" + if (persisted) " (${seen.size - before} new remembered)." else ".")
        return ctx.output(kept)
    }

    private fun stateName(ctx: StepContext) = "dedupe-${ctx.flow.id}-${ctx.step.id}.state"

    companion object {
        const val MAX_REMEMBERED = 20_000

        /** The poll Step's params, shown only while "Items come from" is a web feed; its state file name and reset also serve "across Runs". */
        private fun pollParams(): List<ParamSpec> =
            PollChangesNode(NoopDeviceGateway).spec.params.map { p ->
                when {
                    p.key == "stateKey" -> p.copy(label = "Memory name", default = "", advanced = true,
                        hint = "Where the seen set is kept. Leave empty to use one private to this Step.",
                        showWhen = mapOf("scope" to listOf("always", "day"), "source" to listOf("url")))
                    p.key == "reset" -> p.copy(label = "Forget what was seen",
                        showWhen = mapOf("scope" to listOf("always", "day"), "source" to listOf("url")))
                    p.showWhen.isEmpty() -> p.copy(showWhen = mapOf("source" to listOf("url")))
                    else -> p
                }
            }
    }
}
