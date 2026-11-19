package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.engine.stepdata.PinJson
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.state.DEFAULT_MANUAL_INPUT
import com.kaivoflow.feature.canvas.inspector.state.InputOption
import com.kaivoflow.feature.canvas.inspector.state.InputSource
import com.kaivoflow.feature.canvas.inspector.state.ManualInputError
import com.kaivoflow.feature.canvas.inspector.state.TestRisk

/**
 * Which input a Test Step takes. The automatic order is: pinned data, else the parent's last stored output,
 * else the Step's own last Test input, else a manual input the user edits. A Spark has no parent: it always
 * starts from its editable sample event.
 */
object TestInputPolicy {
    private val order = listOf(InputSource.Pinned, InputSource.LastRun, InputSource.LastTest)

    fun auto(options: List<InputOption>, isSpark: Boolean): InputSource {
        if (isSpark) return InputSource.Sample
        return order.firstOrNull { src -> options.any { it.source == src && it.itemCount > 0 } } ?: InputSource.Manual
    }

    /** The pick when it can still be honoured, else [auto]. Text sources are always available. */
    fun effective(selection: InputSource?, options: List<InputOption>, isSpark: Boolean): InputSource {
        if (isSpark) return InputSource.Sample
        if (selection == null) return auto(options, false)
        if (selection == InputSource.Sample) return InputSource.Manual
        if (selection.isText || options.any { it.source == selection && it.itemCount > 0 }) return selection
        return auto(options, false)
    }
}

/** Reads the manual input text with the strict pin reader: an array of objects or one object, with the failing position. */
object ManualInput {
    sealed interface Parsed {
        data class Items(val items: List<Item>) : Parsed
        data class Bad(val error: ManualInputError) : Parsed
    }

    fun parse(text: String): Parsed = when (val r = PinJson.items(text)) {
        is PinJson.Parsed.Items -> Parsed.Items(r.items)
        is PinJson.Parsed.Error -> Parsed.Bad(ManualInputError(r.message.replace("Nothing to pin: ", "Nothing to test with: "), r.line, r.column))
    }

    fun errorOf(text: String): ManualInputError? = (parse(text) as? Parsed.Bad)?.error
}

/** A readable JSON writer for the manual editor (two-space indent). Accepts maps, lists, strings, numbers, booleans and null. */
object JsonPretty {
    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0) }.toString()

    private fun append(sb: StringBuilder, v: Any?, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> sb.append(if (v is Double && v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Map<*, *> -> if (v.isEmpty()) sb.append("{}") else {
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    indent(sb, depth + 1); quote(sb, k.toString()); sb.append(": "); append(sb, x, depth + 1)
                }
                sb.append('\n'); indent(sb, depth); sb.append('}')
            }
            is List<*> -> if (v.isEmpty()) sb.append("[]") else {
                sb.append("[\n")
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(",\n")
                    indent(sb, depth + 1); append(sb, x, depth + 1)
                }
                sb.append('\n'); indent(sb, depth); sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) = repeat(depth) { sb.append("  ") }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

/** Builds a sample input from the shape [com.kaivoflow.core.model.SchemaInference] found: one item with a value of the right type in every field. */
object SampleFromSchema {
    private const val MAX_DEPTH = 8

    /** A one-item array as text; `[ {} ]` when there is no shape to follow. */
    fun generate(root: SchemaNode?): String {
        val value = root?.takeIf { it.type == SchemaType.Object && it.fields.isNotEmpty() }?.let { value(it, 0) } ?: return DEFAULT_MANUAL_INPUT
        return JsonPretty.write(listOf(value))
    }

    private fun value(n: SchemaNode, depth: Int): Any? {
        if (depth > MAX_DEPTH) return null
        val type = if (n.type == SchemaType.Mixed) n.mixed.firstOrNull { it != SchemaType.Null } ?: SchemaType.Null else n.type
        return when (type) {
            SchemaType.Null -> null
            SchemaType.Bool -> n.sample?.toBooleanStrictOrNull() ?: false
            SchemaType.Number -> n.sample?.toLongOrNull() ?: n.sample?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0L
            SchemaType.String -> n.sample ?: "text"
            SchemaType.Date -> n.sample ?: "2025-01-01T00:00:00Z"
            SchemaType.Object -> LinkedHashMap<String, Any?>().also { out ->
                n.fields.forEach { (k, f) -> if (k != com.kaivoflow.core.model.SchemaInference.BINARY_KEY) out[k] = value(f, depth + 1) }
            }
            SchemaType.Array -> n.element?.let { listOf(value(it, depth + 1)) } ?: emptyList<Any?>()
            SchemaType.Binary -> null
            SchemaType.Mixed -> null
        }
    }
}

/**
 * Steps that act in the outside world. Testing one runs it for real, so the inspector asks first. This list is conservative:
 * it flags every Step that sends, posts, writes or changes something outside the Flow, because the Step specs carry no
 * side-effect metadata yet.
 */
object TestRisks {
    private val posting = mapOf(
        "device.sms" to "sends an SMS",
        "device.notify" to "shows a notification",
        "app.slack" to "posts to Slack",
        "app.discord" to "posts to Discord",
        "app.telegram" to "sends a Telegram message",
        "app.notion" to "changes Notion",
        "app.google_sheets" to "writes to Google Sheets",
        "saas.connector" to "calls an outside service",
        "saas.graphql" to "calls an outside service",
        "saas.webdav" to "writes to a WebDAV server",
        "device.calendar_event" to "adds a calendar event",
        "device.contact" to "changes a contact",
        "device.file" to "writes a file",
        "device.share" to "opens the share sheet",
        "device.open" to "opens another app",
        "device.speak" to "speaks aloud",
        "device.vibrate" to "vibrates the phone",
        "device.torch" to "switches the torch",
        "device.volume" to "changes the volume",
        "flow.subflow" to "runs another Flow for real",
    )

    /** The risk of running a Step of [type] with [values], or null for a Step that only reads and transforms data. */
    fun of(type: String, values: Map<String, ParamValue?>): TestRisk? {
        if (type.startsWith("spark.")) return null
        posting[type]?.let { return TestRisk(it) }
        return when (type) {
            "net.http" -> {
                val raw = (values["method"] as? ParamValue.Fixed)?.value?.toString()
                val unknown = values["method"] is ParamValue.Expr
                if (unknown || (raw != null && raw.trim().uppercase() !in setOf("GET", "HEAD"))) TestRisk("sends an HTTP request that may change data") else null
            }
            "device.clipboard" -> {
                val op = (values["operation"] as? ParamValue.Fixed)?.value?.toString()?.lowercase()
                if (op == null || op != "read") TestRisk("changes the clipboard") else null
            }
            else -> null
        }
    }
}
