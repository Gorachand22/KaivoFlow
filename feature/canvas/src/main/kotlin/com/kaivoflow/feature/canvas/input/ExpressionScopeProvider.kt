package com.kaivoflow.feature.canvas.input

import com.kaivoflow.core.expressions.assist.ExpressionAssist
import com.kaivoflow.core.expressions.assist.KaivoExpressionAssist
import com.kaivoflow.core.expressions.assist.PendingReason
import com.kaivoflow.core.expressions.assist.PreviewContext
import com.kaivoflow.core.expressions.assist.PreviewResult
import com.kaivoflow.core.expressions.assist.SchemaScope
import com.kaivoflow.feature.canvas.inspector.state.PreviewState

/**
 * What a screen tells [KaivoInputField] about the data its expressions can read. Nodes use the
 * inspector's own scope (upstream schema); every other screen supplies a [GlobalExpressionScope].
 */
interface ExpressionScopeProvider {
    /** Suggestions and diagnostics; null gives chips and the accessory bar only. */
    val assist: ExpressionAssist?

    /** Names and shapes for diagnostics and suggestions. */
    val scope: SchemaScope

    /** The live preview of [text], or null for none. Must not throw. */
    fun preview(text: String): PreviewState?

    /** Opens a mapper tray for this field; null hides the Fields button. */
    val onOpenMapper: (() -> Unit)? get() = null

    /** Tap-to-map items shown in the built-in tray when [onOpenMapper] is null. */
    val mapperItems: List<MapperItem> get() = emptyList()
}

/** One tap-to-insert reference: [snippet] is the full `{{ ... }}` text. */
data class MapperItem(val label: String, val snippet: String)

object ExpressionInsert {
    /** Adds [snippet] at the end of [text], separated by a space unless the text is empty or ends in whitespace. */
    fun append(text: String, snippet: String): String =
        if (text.isEmpty() || text.last().isWhitespace()) text + snippet else "$text $snippet"
}

/**
 * Global scope for agents, variables and credentials: `$vars`, `$now`, `$today` and flow info.
 * No item data and no environment, so `$json` previews as unavailable. [vars] are the values `$vars` shows.
 */
class GlobalExpressionScope(
    private val vars: Map<String, Any?> = emptyMap(),
    private val flowId: String = "",
    private val flowName: String = "",
    private val timezone: String = "UTC",
    override val onOpenMapper: (() -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ExpressionScopeProvider {
    private val kaivoAssist = KaivoExpressionAssist()
    override val assist: ExpressionAssist get() = kaivoAssist
    override val scope: SchemaScope = SchemaScope(varNames = vars.keys)

    override val mapperItems: List<MapperItem> = buildList {
        vars.keys.take(40).forEach { add(MapperItem("\$vars.$it", "{{ \$vars.$it }}")) }
        add(MapperItem("\$now", "{{ \$now }}"))
        add(MapperItem("\$today", "{{ \$today }}"))
        if (flowName.isNotEmpty() || flowId.isNotEmpty()) add(MapperItem("\$run.flowName", "{{ \$run.flowName }}"))
    }

    override fun preview(text: String): PreviewState? {
        if (!text.contains("{{")) return null
        return try {
            val ctx = PreviewContext(
                stepName = flowName,
                itemIndex = 0,
                inputs = emptyList(),
                vars = vars,
                runId = flowId.ifEmpty { "preview" },
                timezone = timezone,
                clock = clock,
                flowId = flowId,
                flowName = flowName,
            )
            ExpressionPreviews.stateOf(kaivoAssist.preview(text, ctx))
        } catch (e: Exception) {
            PreviewState.Error(e.message ?: "Preview failed")
        }
    }
}

object ExpressionPreviews {
    const val GLOBAL_ONLY = "Only \$vars, \$now, \$today and flow info are available here."

    fun stateOf(r: PreviewResult): PreviewState = when (r) {
        is PreviewResult.Value -> PreviewState.Value(r.rendered, r.type.name.lowercase())
        is PreviewResult.Pending -> PreviewState.Pending(
            if (r.reason == PendingReason.NoUpstreamData) GLOBAL_ONLY else "That Step is not available here.",
        )
        is PreviewResult.Error -> PreviewState.Error(r.error.detail, r.span?.start, r.span?.end)
    }
}
