package com.kaivoflow.feature.canvas.inspector.mapper

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.expressions.assist.ReferenceTarget
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.params.AssignmentsModel
import com.kaivoflow.feature.canvas.inspector.params.ConditionsModel
import com.kaivoflow.feature.canvas.inspector.params.KeyValueModel
import com.kaivoflow.feature.canvas.inspector.params.ParamTree

/** What dropping or tapping a reference does to one parameter. The same function serves drag and tap-to-insert, so both give the same text. */
sealed interface InsertOutcome {
    /** The parameter's new stored value. */
    data class Set(val value: ParamValue) : InsertOutcome
    /** The target cannot take data references: shake and snackbar. */
    data object Rejected : InsertOutcome
}

object MapperInsert {

    fun acceptsData(def: ParamDef): Boolean = def.expr != ExprMode.Never && when (def.kind) {
        is ParamKind.Code, ParamKind.Secret, is ParamKind.CredentialPick, is ParamKind.Notice -> false
        else -> true
    }

    /**
     * [current] is the stored value (or null). [selStart] and [selEnd] are the caret in the target
     * field, -1 when unknown. Containers add a row; plain fields flip to expression mode (keeping
     * the old literal for the undo bar, which the form takes from its own before and after).
     */
    fun apply(
        def: ParamDef,
        current: ParamValue?,
        source: MapSource,
        schemaType: SchemaType? = null,
        selStart: Int = -1,
        selEnd: Int = -1,
    ): InsertOutcome {
        if (!acceptsData(def)) return InsertOutcome.Rejected
        val reference = ReferenceBuilder.build(source, ReferenceTarget.Template)
        val value = current ?: def.default
        return when (val k = def.kind) {
            is ParamKind.Assignments -> {
                val rows = AssignmentsModel.decode(value)
                InsertOutcome.Set(AssignmentsModel.encode(AssignmentsModel.fromDrop(rows, source, reference, schemaType, k.allowedTypes))!!)
            }
            ParamKind.KeyValueList -> {
                val rows = KeyValueModel.decode(value)
                InsertOutcome.Set(KeyValueModel.encode(KeyValueModel.fromDrop(rows, source, reference), KeyValueModel.wasMap(value))!!)
            }
            is ParamKind.Conditions -> {
                val tree = ConditionsModel.decode(value)
                InsertOutcome.Set(ConditionsModel.encode(ConditionsModel.dropAsNewRule(tree, tree.root.id, reference, schemaType))!!)
            }
            is ParamKind.Repeater -> {
                val child = k.children.firstOrNull { acceptsData(it) && it.kind is ParamKind.Text } ?: return InsertOutcome.Rejected
                val rows = ParamTree.asRows(value)
                val max = k.maxItems
                if (max != null && rows.size >= max) return InsertOutcome.Rejected
                val row = ParamTree.withChild(emptyMap(), child.key, ParamValue.Expr(reference))
                InsertOutcome.Set(ParamValue.Fixed(rows + listOf(row)))
            }
            is ParamKind.Group -> {
                val child = k.children.firstOrNull { acceptsData(it) && it.kind is ParamKind.Text } ?: return InsertOutcome.Rejected
                InsertOutcome.Set(ParamValue.Fixed(ParamTree.withChild(ParamTree.asMap(value), child.key, ParamValue.Expr(reference))))
            }
            is ParamKind.Collection -> {
                val present = ParamTree.asMap(value)
                val child = k.optional.firstOrNull { acceptsData(it) && it.kind is ParamKind.Text && !present.containsKey(it.key) } ?: return InsertOutcome.Rejected
                InsertOutcome.Set(ParamValue.Fixed(ParamTree.withChild(present, child.key, ParamValue.Expr(reference))))
            }
            is ParamKind.ResourcePick -> {
                val m = ParamTree.asMap(value)
                val withMode = if (m["mode"] == null) ParamTree.withChild(m, "mode", ParamValue.Fixed("id")) else m
                InsertOutcome.Set(ParamValue.Fixed(ParamTree.withChild(withMode, "value", ParamValue.Expr(reference))))
            }
            is ParamKind.FileRef -> {
                val m = ParamTree.asMap(value)
                if ((m["mode"]?.toString()?.lowercase() ?: "data").let { it == "device" || it == "fromdevice" }) return InsertOutcome.Rejected
                val withMode = ParamTree.withChild(m, "mode", ParamValue.Fixed("data"))
                InsertOutcome.Set(ParamValue.Fixed(ParamTree.withChild(withMode, "property", ParamValue.Expr(reference))))
            }
            else -> InsertOutcome.Set(scalarValue(def, value, source, reference, selStart, selEnd))
        }
    }

    private fun scalarValue(def: ParamDef, value: ParamValue?, source: MapSource, reference: String, selStart: Int, selEnd: Int): ParamValue {
        val hasCaret = selStart >= 0
        return when {
            value is ParamValue.Expr -> {
                val at = if (hasCaret) selStart else value.text.length
                ParamValue.Expr(ReferenceBuilder.insertInto(value.text, at, if (hasCaret) selEnd else at, source).text)
            }
            // A JSON value stays fixed: the reference goes into the text (inside a string literal it goes unquoted).
            def.kind is ParamKind.Json && hasCaret && value is ParamValue.Fixed && value.value is String ->
                ParamValue.Fixed(ReferenceBuilder.insertInto(value.value as String, selStart, selEnd, source).text)
            hasCaret && value is ParamValue.Fixed && value.value is String && (value.value as String).isNotEmpty() && def.kind is ParamKind.Text ->
                ParamValue.Expr(ReferenceBuilder.insertInto(value.value as String, selStart, selEnd, source).text)
            else -> ParamValue.Expr(reference)
        }
    }
}
