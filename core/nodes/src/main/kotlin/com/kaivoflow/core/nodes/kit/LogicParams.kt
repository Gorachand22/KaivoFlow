package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import kotlinx.collections.immutable.persistentListOf

internal object LogicParams {
    fun conditionParams() = persistentListOf(
        ParamSpec(
            key = "combinator",
            label = "Match",
            type = ParamType.Select,
            default = "all",
            options = persistentListOf(ParamOption("all", "All conditions"), ParamOption("any", "Any condition")),
        ),
        ParamSpec(
            key = "conditions",
            label = "Conditions",
            type = ParamType.KeyValue,
            hint = "left = expression or field, operator, right = value",
        ),
    )
}

/** Shared by both branch Step specs. */
internal fun ParamSpecList(vararg specs: ParamSpec) = persistentListOf(*specs)
