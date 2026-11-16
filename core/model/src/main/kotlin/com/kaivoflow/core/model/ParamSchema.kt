package com.kaivoflow.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The one description of a Step's parameters: the inspector renders it, the validator checks
 * against it, the resolver applies defaults and show rules from it. Pure Kotlin, no Android.
 *
 * Spec: docs/specs/P2-data-panels-params.md sections 2 and 4.1.
 */
data class ParamSchema(
    val version: Int,
    val params: ImmutableList<ParamDef>,
    val credentialSlots: ImmutableList<CredentialSlot> = persistentListOf(),
    /** Visual sections; a [ParamDef.group] names one of these ids. */
    val groups: ImmutableList<ParamGroup> = persistentListOf(),
) {
    fun find(key: String): ParamDef? = params.firstOrNull { it.key == key }

    companion object {
        val Empty = ParamSchema(version = 1, params = persistentListOf())
    }
}

data class ParamGroup(val id: String, val label: String)

/** A credential a Step needs. Never templated (credential ids are not data). */
data class CredentialSlot(
    val key: String,
    val label: String,
    val accepts: ImmutableSet<CredentialKind> = persistentSetOf(),
    val required: Boolean = true,
    val show: ShowWhen = ShowWhen.Always,
)

/** Broad credential families a picker can filter by. */
enum class CredentialKind { ApiKey, Bearer, Basic, OAuth2, Header, Database, Custom }

data class ParamDef(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val required: Boolean = false,
    val default: ParamValue? = null,
    val hint: String? = null,
    val placeholder: String? = null,
    val expr: ExprMode = ExprMode.Allowed,
    val rules: ImmutableList<ParamRule> = persistentListOf(),
    val show: ShowWhen = ShowWhen.Always,
    val advanced: Boolean = false,
    val group: String? = null,
    /** Long text for the About tab. */
    val docs: String? = null,
)

/** Whether a parameter may hold an expression. */
enum class ExprMode {
    /** The value is never expanded (Secret, Code, credential ids, operators). */
    Never,

    /** Fixed by default, can switch to an expression. */
    Allowed,

    /** Always an expression; bare text is the expression body. */
    Only,
}

enum class TextFormat { Plain, Url, Email, Phone, Path, Template }
enum class DateTimeMode { Date, DateTime, Time }
enum class JsonShape { Any, Object, List }
enum class CodeLanguage { JavaScript, Json, Sql, Plain }
enum class ResourceMode { FromList, ByUrl, ById }
enum class NoticeTone { Info, Warning, Danger }
enum class ValueType {
    Text, Number, Boolean, List, Object, Any;

    companion object {
        val all: ImmutableSet<ValueType> get() = persistentSetOf(*entries.toTypedArray())
    }
}
enum class CmpOp { Gt, Ge, Lt, Le }

data class NoticeAction(val label: String, val id: String)

sealed interface ParamKind {
    data class Text(val multiline: Boolean = false, val format: TextFormat = TextFormat.Plain) : ParamKind
    data class Number(
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val integer: Boolean = false,
        val unit: String? = null,
    ) : ParamKind
    data object Bool : ParamKind
    data class Choice(val options: OptionSource, val searchable: Boolean = false) : ParamKind
    data class MultiChoice(val options: OptionSource, val min: Int = 0, val max: Int? = null) : ParamKind
    data class DateTime(val mode: DateTimeMode = DateTimeMode.DateTime) : ParamKind
    data class Json(val expect: JsonShape = JsonShape.Any) : ParamKind
    data class Code(val language: CodeLanguage = CodeLanguage.Plain) : ParamKind
    data object Color : ParamKind

    /** Optional fields bag: value is a map holding only the chosen children. */
    data class Collection(val optional: ImmutableList<ParamDef>) : ParamKind

    /** One object with fixed children. */
    data class Group(val children: ImmutableList<ParamDef>) : ParamKind

    /** A list of objects, each with the same children. */
    data class Repeater(
        val children: ImmutableList<ParamDef>,
        val minItems: Int = 0,
        val maxItems: Int? = null,
        val itemLabelKey: String? = null,
    ) : ParamKind
    data class Assignments(val allowedTypes: ImmutableSet<ValueType> = ValueType.all) : ParamKind
    data class Conditions(val allowNested: Boolean = false) : ParamKind
    data class ResourcePick(
        val modes: ImmutableSet<ResourceMode>,
        val loader: String? = null,
        val urlPattern: String? = null,
    ) : ParamKind
    data class CredentialPick(val accepts: ImmutableSet<CredentialKind> = persistentSetOf()) : ParamKind
    data class Notice(val tone: NoticeTone, val action: NoticeAction? = null) : ParamKind
    data object Secret : ParamKind
    data class FileRef(val accept: ImmutableSet<String> = persistentSetOf(), val output: Boolean = false) : ParamKind

    /**
     * Name and value rows (headers, query, form fields). Stored as a map `name to value`, or as a
     * list of `{name, value}` rows; both are accepted. Spec 2.19 calls this a Repeater of two text
     * children; [asRepeater] gives that view for widgets.
     */
    data object KeyValueList : ParamKind {
        fun asRepeater(): Repeater = Repeater(
            children = persistentListOf(
                ParamDef("name", "Name", Text(), required = true),
                ParamDef("value", "Value", Text()),
            ),
            itemLabelKey = "name",
        )
    }
}

sealed interface OptionSource {
    data class Static(val options: ImmutableList<ParamOption>) : OptionSource
    data class Dynamic(val loader: String, val dependsOn: ImmutableSet<String> = persistentSetOf()) : OptionSource
}

/**
 * Conditional display. Paths address siblings (`mode`), parents (`../auth`, `../../x`) and the
 * root (`/operation`); a dotted tail (`body.kind`) reads into an object value.
 */
sealed interface ShowWhen {
    data object Always : ShowWhen
    data class All(val of: List<ShowWhen>) : ShowWhen
    data class Any(val of: List<ShowWhen>) : ShowWhen
    data class Not(val of: ShowWhen) : ShowWhen
    data class Eq(val path: String, val value: kotlin.Any?) : ShowWhen
    data class Ne(val path: String, val value: kotlin.Any?) : ShowWhen
    data class In(val path: String, val values: List<kotlin.Any?>) : ShowWhen
    data class Cmp(val path: String, val op: CmpOp, val value: Double) : ShowWhen
    data class Exists(val path: String) : ShowWhen

    /** True when the param at [path] is unset, blank or an empty list or object. */
    data class Empty(val path: String) : ShowWhen
    data class CredentialSet(val slot: String) : ShowWhen
}

/** A stored parameter value: explicit mode, never inferred from text (except by the legacy reader). */
sealed interface ParamValue {
    data class Fixed(val value: kotlin.Any?) : ParamValue
    data class Expr(val text: String) : ParamValue
}

sealed interface ParamRule {
    data object Required : ParamRule
    data class MinLength(val n: Int) : ParamRule
    data class MaxLength(val n: Int) : ParamRule
    data class Min(val n: Double) : ParamRule
    data class Max(val n: Double) : ParamRule
    data class Pattern(val regex: String) : ParamRule
    data class OneOf(val values: List<String>) : ParamRule
    data object JsonValid : ParamRule
    data object UrlValid : ParamRule

    /** Named validator registered in code (see [ValidationContext.custom]); never a lambda in schema data. */
    data class Custom(val id: String) : ParamRule
}

/** Where an issue is: segments from the root, e.g. `headers`, `[2]`, `name`. Rendered `headers[2].name`. */
data class ParamPath(val segments: List<String> = emptyList()) {
    fun child(key: String) = ParamPath(segments + key)
    fun index(i: Int) = ParamPath(segments + "[$i]")

    override fun toString(): String = buildString {
        segments.forEachIndexed { i, s ->
            if (s.startsWith("[") || i == 0) append(s) else append('.').append(s)
        }
    }

    companion object {
        val Root = ParamPath()
        fun of(vararg keys: String) = ParamPath(keys.toList())
    }
}

enum class ParamSeverity { Error, Warning }

data class ParamIssue(
    val path: ParamPath,
    val severity: ParamSeverity,
    /** Stable machine code, also the resource id of the message (for example `required.missing`). */
    val code: String,
    val message: String,
)
