package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.BinarySupport
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max

/** Build a crisp SVG chart from items — no chart library, no network, no tracking. */
class ChartNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.chart",
        title = "Quick chart",
        category = StepCategory.Transform,
        subtitle = "Draw a bar or line chart from your items",
        iconKey = "queryStats",
        params = persistentListOf(
            ParamSpec(
                key = "style",
                label = "Chart",
                type = ParamType.Select,
                default = "bar",
                options = selectOptions("bar" to "Bars", "line" to "Line", "area" to "Area"),
            ),
            textParam("labelField", "Label field", default = "name", placeholder = "month"),
            textParam("valueField", "Value field", default = "value", placeholder = "amount"),
            ParamSpec("width", "Width (px)", ParamType.Number, default = 640),
            ParamSpec("height", "Height (px)", ParamType.Number, default = 320),
            textParam("outputField", "Save SVG in", default = "svg"),
        ),
        keywords = persistentListOf("chart", "graph", "svg", "bar", "line", "visualise", "plot", "report"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val labelField = Values.asString(params["labelField"], "name")
        val valueField = Values.asString(params["valueField"], "value")
        val width = (Values.asDouble(params["width"]) ?: 640.0).toInt().coerceIn(240, 1600)
        val height = (Values.asDouble(params["height"]) ?: 320.0).toInt().coerceIn(160, 1000)
        val style = Values.asString(params["style"], "bar")
        val points = ctx.input.mapIndexed { index, item ->
            val label = Values.asString(Values.deepGet(item.json, labelField), "Item ${index + 1}")
            val value = Values.asDouble(Values.deepGet(item.json, valueField)) ?: 0.0
            ChartPoint(label, value)
        }.take(200)
        if (points.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one item to chart")
        val svg = ChartSvg.render(points, width, height, style)
        val base64 = Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        val map = gateway.storeBinary("chart.svg", "image/svg+xml", base64)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the chart could not be saved")
        val ref = BinarySupport.ref(map)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the chart reference is incomplete")
        val field = ctx.outputName("svg")
        return ctx.output(
            listOf(
                FlowData(
                    mapOf(
                        field to svg,
                        "fileName" to ref.fileName,
                        "points" to points.map { mapOf("label" to it.label, "value" to it.value) },
                    ),
                ).withBinary("chart", ref)
            )
        )
    }
}

private data class ChartPoint(val label: String, val value: Double)

private object ChartSvg {
    fun render(points: List<ChartPoint>, width: Int, height: Int, style: String): String {
        val left = 52.0
        val right = 16.0
        val top = 20.0
        val bottom = 44.0
        val plotW = width - left - right
        val plotH = height - top - bottom
        val maxValue = max(1.0, points.maxOf { abs(it.value) })
        val baseline = top + plotH
        fun x(index: Int): Double = left + (index + 0.5) * plotW / points.size
        fun y(value: Double): Double = baseline - value / maxValue * plotH
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
            .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
            .append(width).append(' ').append(height).append("\">")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#0B0F19\"/>")
        sb.append("<g stroke=\"#2A3142\" stroke-width=\"1\">")
        for (i in 0..4) {
            val gy = top + plotH * i / 4.0
            sb.append("<line x1=\"$left\" y1=\"$gy\" x2=\"${left + plotW}\" y2=\"$gy\"/>")
        }
        sb.append("</g>")
        sb.append("<g fill=\"#8A93A8\" font-family=\"sans-serif\" font-size=\"11\">")
        for (i in 0..4) {
            val value = maxValue * (4 - i) / 4.0
            val gy = top + plotH * i / 4.0
            sb.append("<text x=\"8\" y=\"${gy + 4}\">").append(escape(fmt(value))).append("</text>")
        }
        sb.append("</g>")
        when (style) {
            "line", "area" -> {
                val path = points.mapIndexed { index, point ->
                    (if (index == 0) "M" else "L") + "${fmt(x(index))},${fmt(y(point.value))}"
                }.joinToString(" ")
                if (style == "area") {
                    sb.append("<path d=\"$path L${fmt(x(points.size - 1))},$baseline L${fmt(x(0))},$baseline Z\" fill=\"#D4AF37\" fill-opacity=\"0.16\"/>")
                }
                sb.append("<path d=\"$path\" fill=\"none\" stroke=\"#E5C158\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
                points.forEachIndexed { index, point ->
                    sb.append("<circle cx=\"${fmt(x(index))}\" cy=\"${fmt(y(point.value))}\" r=\"4\" fill=\"#F59E0B\"/>")
                }
            }
            else -> {
                val barW = plotW / points.size * 0.62
                points.forEachIndexed { index, point ->
                    val px = x(index) - barW / 2.0
                    val py = y(point.value)
                    sb.append("<rect x=\"${fmt(px)}\" y=\"${fmt(py)}\" width=\"${fmt(barW)}\"")
                        .append(" height=\"").append(fmt(baseline - py))
                        .append("\" rx=\"3\" fill=\"#D4AF37\"/>")
                }
            }
        }
        sb.append("<g fill=\"#C9D0E0\" font-family=\"sans-serif\" font-size=\"11\" text-anchor=\"middle\">")
        points.forEachIndexed { index, point ->
            sb.append("<text x=\"${fmt(x(index))}\" y=\"${baseline + 20}\">")
                .append(escape(point.label.take(14))).append("</text>")
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    private fun fmt(value: Double): String =
        if (abs(value - value.toLong()) < 0.001) value.toLong().toString() else String.format("%.2f", value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
