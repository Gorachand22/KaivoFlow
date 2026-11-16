package com.kaivoflow.core.model

import kotlinx.collections.immutable.toImmutableList

/**
 * Compat bridge for the migration period: lets today's [ParamSpec] catalog feed the new
 * [ParamSchema] pipeline (validator, resolver) without rewriting any node.
 * Deleted when the last node family is migrated (spec task T6).
 */
fun ParamSpec.toDef(): ParamDef {
    val kind: ParamKind = when (type) {
        ParamType.Text -> ParamKind.Text()
        ParamType.Multiline -> ParamKind.Text(multiline = true)
        ParamType.Number -> ParamKind.Number()
        ParamType.Boolean -> ParamKind.Bool
        ParamType.Select -> ParamKind.Choice(OptionSource.Static(options))
        ParamType.Json -> ParamKind.Json()
        ParamType.Expression -> ParamKind.Text(multiline = true)
        ParamType.Url -> ParamKind.Text(format = TextFormat.Url)
        ParamType.Secret -> ParamKind.Secret
        ParamType.KeyValue -> ParamKind.KeyValueList
        ParamType.Code -> ParamKind.Code(CodeLanguage.Plain)
    }
    val exprMode = when {
        type == ParamType.Secret || type == ParamType.Code -> if (supportsExpression) ExprMode.Allowed else ExprMode.Never
        type == ParamType.Expression -> ExprMode.Only
        supportsExpression -> ExprMode.Allowed
        else -> ExprMode.Never
    }
    val show: ShowWhen = when (showWhen.size) {
        0 -> ShowWhen.Always
        1 -> showWhen.entries.first().let { ShowWhen.In(it.key, it.value) }
        // Today's inspector shows a field when ANY listed sibling matches.
        else -> ShowWhen.Any(showWhen.entries.map { ShowWhen.In(it.key, it.value) })
    }
    return ParamDef(
        key = key,
        label = label,
        kind = kind,
        required = required,
        default = ParamValueCodec.decodeLegacy(default),
        hint = hint.ifBlank { null },
        placeholder = placeholder.ifBlank { null },
        expr = exprMode,
        show = show,
        advanced = advanced,
    )
}

/** The schema view of a Step's legacy parameter list. */
fun NodeSpec.toSchema(): ParamSchema = paramSchema ?: ParamSchema(version = 1, params = params.map { it.toDef() }.toImmutableList())

/**
 * Returns a copy of this spec whose schema is the compat schema with the listed keys switched to a
 * native [ParamKind] (for example `Assignments`). Keys and order stay those of [NodeSpec.params].
 */
fun NodeSpec.withKinds(vararg kinds: Pair<String, ParamKind>): NodeSpec {
    val byKey = kinds.toMap()
    val base = ParamSchema(version = 1, params = params.map { it.toDef() }.toImmutableList())
    val missing = byKey.keys - params.map { it.key }.toSet()
    require(missing.isEmpty()) { "withKinds: unknown params $missing in $id" }
    return copy(paramSchema = base.copy(params = base.params.map { d -> byKey[d.key]?.let { d.copy(kind = it) } ?: d }.toImmutableList()))
}
