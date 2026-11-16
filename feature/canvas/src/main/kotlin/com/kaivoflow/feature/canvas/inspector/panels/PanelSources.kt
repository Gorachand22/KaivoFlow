package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.SourceKind
import com.kaivoflow.core.model.SchemaPath

/**
 * Which Step the panel is showing, so a drag or tap produces the right reference: from the
 * immediate parent it is `{{ $json.x }}`, from another Step `$step("Name")[i]...`.
 */
data class PanelOrigin(
    val stepId: String,
    val stepName: String,
    val isImmediateParent: Boolean = true,
)

/** Builds the [MapSource] values the inspector intents take. */
object PanelSources {
    fun json(origin: PanelOrigin, path: SchemaPath, itemIndex: Int = 0): MapSource =
        MapSource(origin.stepId, origin.isImmediateParent, path, SourceKind.Json, origin.stepName, itemIndex)

    fun column(origin: PanelOrigin, column: TableColumn, itemIndex: Int = 0): MapSource =
        json(origin, column.path, itemIndex)

    /** The file name of attachment [name]: `$binary.photo.fileName`. */
    fun binaryFileName(origin: PanelOrigin, name: String, itemIndex: Int = 0): MapSource =
        MapSource(origin.stepId, origin.isImmediateParent, SchemaPath(listOf(name, "fileName")), SourceKind.Binary, origin.stepName, itemIndex)

    /** A JSON node as a concrete path inside item [itemIndex]. */
    fun node(origin: PanelOrigin, row: JsonRow): MapSource = json(origin, row.path, row.itemIndex)
}
