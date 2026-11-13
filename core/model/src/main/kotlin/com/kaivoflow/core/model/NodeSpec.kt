package com.kaivoflow.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Catalog metadata for a Step type. The canvas never hardcodes knowledge about a Step:
 * the palette, the inspector form and the validator are all driven by this spec.
 */
data class NodeSpec(
    val id: String,
    val title: String,
    val category: StepCategory,
    /** Short, human sentence shown under the title. Never technical jargon. */
    val subtitle: String,
    val iconKey: String,
    val kind: NodeKind = NodeKind.Action,
    /** Number of output ports. 1 for a linear Step, 2+ for branching Steps. */
    val outputs: Int = 1,
    val outputLabels: ImmutableList<String> = persistentListOf(),
    val params: ImmutableList<ParamSpec> = persistentListOf(),
    /** Search aliases, including the legacy n8n name so users can find "HTTP Request" etc. */
    val keywords: ImmutableList<String> = persistentListOf(),
    /** True when the Step needs a sensitive Android permission (Pillar 4 disclosure). */
    val requiresSensitivePermission: String? = null,
    /** True when the Step can be the first Step of a Flow. */
    val canStartFlow: Boolean = false,
    val producesItems: Boolean = true,
) {
    val isTrigger: Boolean get() = kind == NodeKind.Trigger
}

enum class NodeKind { Trigger, Action }

enum class StepCategory(val label: String) {
    Trigger("Phone Triggers"),
    Ai("AI & LLMs"),
    Saas("SaaS Apps"),
    Logic("Logic"),
    Transform("Transform"),
    Device("Device"),
    ;

    companion object {
        fun fromKey(key: String): StepCategory =
            entries.firstOrNull { it.name.equals(key, true) } ?: Transform
    }
}

data class ParamSpec(
    val key: String,
    val label: String,
    val type: ParamType,
    val required: Boolean = false,
    val default: Any? = null,
    val placeholder: String = "",
    val hint: String = "",
    val options: ImmutableList<ParamOption> = persistentListOf(),
    /** Only show this field when the sibling param equals one of these values. */
    val showWhen: Map<String, List<Any?>> = emptyMap(),
    val supportsExpression: Boolean = type != ParamType.Secret,
    val advanced: Boolean = false,
)

data class ParamOption(val value: String, val label: String)

enum class ParamType {
    Text,
    Multiline,
    Number,
    Boolean,
    Select,
    /** Raw JSON object/array typed by the user; validated by the engine before running. */
    Json,
    /** Explicit expression field: the whole value is evaluated, result type preserved. */
    Expression,
    Url,
    /** Stored in the encrypted vault, never logged. */
    Secret,
    KeyValue,
    Code,
}
