package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.model.CredentialSlot
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamScope
import com.kaivoflow.core.model.ParamSeverity
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ShowRules

/** One parameter to draw: its definition, where it lives, what is stored, and what is wrong with it. */
data class FormField(
    val def: ParamDef,
    val path: ParamPath,
    val value: ParamValue?,
    /** True when a show rule hinged on an expression-controlled param: draw a muted "depends on expression" badge. */
    val dependsOnExpr: Boolean,
    /** Issues at exactly this path. */
    val issues: List<ParamIssue>,
    /** Issues anywhere below this path (container children). */
    val nestedIssueCount: Int,
)

data class FormModel(
    val primary: List<FormField>,
    val advanced: List<FormField>,
    /** Advanced params whose stored value differs from the default: the badge on "More options". */
    val advancedChanged: Int,
    val credentialSlots: List<CredentialSlot>,
)

/**
 * Builds what [ParamForm] draws from a schema and the stored values. Hidden params are left out
 * (their stored values stay in the map, so toggling back restores them), `advanced` params are
 * split off, and an optional search text filters by label and hint.
 */
object FormModelBuilder {

    fun build(
        schema: ParamSchema,
        values: Map<String, ParamValue?>,
        issues: List<ParamIssue> = emptyList(),
        credentialsFilled: Set<String> = emptySet(),
        search: String = "",
    ): FormModel = buildLevel(
        defs = schema.params,
        values = values,
        scope = ParamScope(schema.params, values, null, credentialsFilled),
        at = ParamPath.Root,
        issues = issues,
        search = search,
        slots = schema.credentialSlots.filter {
            ShowRules.evaluate(it.show, ParamScope(schema.params, values, null, credentialsFilled)).visible
        },
    )

    /** One level: the root, a group's children, or one repeater row. */
    fun buildLevel(
        defs: List<ParamDef>,
        values: Map<String, ParamValue?>,
        scope: ParamScope,
        at: ParamPath,
        issues: List<ParamIssue>,
        search: String = "",
        slots: List<CredentialSlot> = emptyList(),
    ): FormModel {
        val q = search.trim().lowercase()
        val primary = ArrayList<FormField>()
        val advanced = ArrayList<FormField>()
        var changed = 0
        for (def in defs) {
            val vis = ShowRules.evaluate(def.show, scope)
            if (!vis.visible) continue
            if (q.isNotEmpty() && !matches(def, q)) continue
            val path = at.child(def.key)
            val value = values[def.key]
            val own = issues.filter { it.path == path }
            val nested = issues.count { it.path != path && startsWith(it.path, path) }
            val field = FormField(def, path, value, vis.dependsOnExpr, own, nested)
            if (def.advanced) {
                advanced += field
                if (value != null && value != def.default) changed++
            } else primary += field
        }
        return FormModel(primary, advanced, changed, slots)
    }

    /** Child scope and decoded values for a Group or one Repeater row. */
    fun childLevel(parent: ParamScope, children: List<ParamDef>, stored: Map<String, Any?>): Pair<ParamScope, Map<String, ParamValue?>> {
        val decoded = children.associate { it.key to ParamTree.child(stored, it.key) }
        return parent.child(children, decoded) to decoded
    }

    fun matches(def: ParamDef, lowerQuery: String): Boolean =
        def.label.lowercase().contains(lowerQuery) || (def.hint?.lowercase()?.contains(lowerQuery) == true) ||
            def.key.lowercase().contains(lowerQuery)

    /** The search icon appears only for Steps with more than 12 params (spec 3.9). */
    fun offersSearch(schema: ParamSchema): Boolean = schema.params.size > 12

    fun startsWith(path: ParamPath, prefix: ParamPath): Boolean =
        path.segments.size >= prefix.segments.size && path.segments.subList(0, prefix.segments.size) == prefix.segments

    fun errorCount(issues: List<ParamIssue>): Int = issues.count { it.severity == ParamSeverity.Error }

    /** Children that make a Collection's "Add field" list: declared optional children not yet present. */
    fun addableOptional(kind: ParamKind.Collection, present: Map<String, Any?>): List<ParamDef> =
        kind.optional.filter { !present.containsKey(it.key) }
}
