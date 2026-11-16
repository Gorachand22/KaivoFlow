package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.model.DateTimeMode
import com.kaivoflow.core.model.JsonShape
import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.core.model.ParamOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Pure logic behind the simpler widgets: choice layout, JSON status and formatting, date text, color hex, code lines, notice markup.

enum class ChoiceLayout { Segmented, Sheet, SearchSheet }

object ChoiceModel {
    /** 2 to 4 options: segmented; 5 to 8: sheet rows; more than 8 or searchable: sheet with search. */
    fun layout(count: Int, searchable: Boolean): ChoiceLayout = when {
        searchable || count > 8 -> ChoiceLayout.SearchSheet
        count <= 4 -> ChoiceLayout.Segmented
        else -> ChoiceLayout.Sheet
    }

    /** A stored value that is no longer among the options: shown as "no longer available", never silently reset. */
    fun isStale(options: List<ParamOption>, value: String?): Boolean =
        !value.isNullOrEmpty() && options.none { it.value == value }

    fun filter(options: List<ParamOption>, query: String): List<ParamOption> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) options else options.filter { it.label.lowercase().contains(q) || it.value.lowercase().contains(q) }
    }

    fun labelOf(options: List<ParamOption>, value: String?): String? = options.firstOrNull { it.value == value }?.label

    fun toggle(selected: List<String>, value: String, max: Int?): List<String> = when {
        value in selected -> selected - value
        max != null && selected.size >= max -> selected
        else -> selected + value
    }

    fun selectAll(options: List<ParamOption>, max: Int?): List<String> = options.map { it.value }.let { if (max != null) it.take(max) else it }

    /** Stored shape of a multi choice: a list, or null when nothing is selected. */
    fun stored(selected: List<String>): List<String>? = selected.ifEmpty { null }
}

object JsonModel {
    sealed interface Status {
        data object Empty : Status
        data class Valid(val kind: String) : Status
        data class Invalid(val message: String, val line: Int, val column: Int) : Status
        data class WrongShape(val expected: String) : Status
    }

    private val EXPR = Regex("""\{\{[\s\S]*?\}\}""")

    /** Expression regions are swapped for `null` first, like the validator does, so templates can be checked while editing. */
    fun status(text: String, expect: JsonShape = JsonShape.Any): Status {
        if (text.isBlank()) return Status.Empty
        val neutral = EXPR.replace(text) { "null".padEnd(it.value.length) }
        return when (val r = ParamJson.parse(neutral)) {
            is ParamJson.Parsed.Failed -> Status.Invalid(r.error.message, r.error.line, r.error.column)
            is ParamJson.Parsed.Ok -> {
                val v = r.value
                val kind = when (v) { is Map<*, *> -> "object"; is List<*> -> "list"; null -> "null"; is String -> "text"; is Boolean -> "boolean"; else -> "number" }
                val ok = when (expect) { JsonShape.Any -> true; JsonShape.Object -> v is Map<*, *> || text.trim().startsWith("{{"); JsonShape.List -> v is List<*> || text.trim().startsWith("{{") }
                if (ok) Status.Valid(kind) else Status.WrongShape(if (expect == JsonShape.Object) "object" else "list")
            }
        }
    }

    /** Pretty prints valid JSON with two spaces. Text that holds expressions outside strings cannot be parsed and gives null. */
    fun format(text: String): String? {
        val parsed = ParamJson.parse(text) as? ParamJson.Parsed.Ok ?: return null
        val sb = StringBuilder()
        write(sb, parsed.value, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        val pad = "  ".repeat(depth + 1)
        when (v) {
            is Map<*, *> -> if (v.isEmpty()) sb.append("{}") else {
                sb.append("{\n")
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(pad).append(ParamJson.write(k.toString())).append(": ")
                    write(sb, x, depth + 1)
                }
                sb.append('\n').append("  ".repeat(depth)).append('}')
            }
            is List<*> -> if (v.isEmpty()) sb.append("[]") else {
                sb.append("[\n")
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(",\n")
                    sb.append(pad)
                    write(sb, x, depth + 1)
                }
                sb.append('\n').append("  ".repeat(depth)).append(']')
            }
            else -> sb.append(ParamJson.write(v))
        }
    }

    /** The bracket helper row: `{ } [ ] " : ,`. */
    val helperSymbols: List<String> = listOf("{", "}", "[", "]", "\"", ":", ",")
}

object CodeModel {
    fun lineCount(text: String): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    /** Symbol bar: tab, brackets, quotes, `=>`. Pairs wrap the selection; the caret lands between an empty pair. */
    val symbols: List<String> = listOf("\t", "(", ")", "{", "}", "[", "]", "\"", "'", "=>")

    fun insertSymbol(text: String, selStart: Int, selEnd: Int, symbol: String): Pair<String, Int> {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val pair = when (symbol) { "(" -> ")"; "{" -> "}"; "[" -> "]"; "\"" -> "\""; "'" -> "'"; else -> null }
        if (pair == null) return text.substring(0, a) + symbol + text.substring(b) to a + symbol.length
        val inner = text.substring(a, b)
        val out = text.substring(0, a) + symbol + inner + pair + text.substring(b)
        return out to (if (inner.isEmpty()) a + 1 else a + inner.length + 2)
    }

    /** No syntax check runs here (nothing is executed); only the size limit is enforced. */
    const val MAX_CHARS = 200_000
}

object ColorHex {
    private val FULL = Regex("^#?([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$")
    private val SHORT = Regex("^#?([0-9a-fA-F]{3})$")

    /** `#RRGGBB` or `#RRGGBBAA` in upper case, or null when the text is not a color. Three digit hex is expanded. */
    fun normalize(text: String, allowAlpha: Boolean = true): String? {
        val t = text.trim()
        SHORT.matchEntire(t)?.let { m -> return "#" + m.groupValues[1].map { "$it$it" }.joinToString("").uppercase() }
        val m = FULL.matchEntire(t) ?: return null
        if (m.groupValues[2].isNotEmpty() && !allowAlpha) return null
        return "#" + (m.groupValues[1] + m.groupValues[2]).uppercase()
    }

    /** Parses to ARGB, or null. `#RRGGBBAA` is read as RGB plus alpha. */
    fun toArgb(hex: String): Int? {
        val n = normalize(hex) ?: return null
        val rgb = n.substring(1, 7).toInt(16)
        val a = if (n.length == 9) n.substring(7, 9).toInt(16) else 255
        return (a shl 24) or rgb
    }

    fun fromArgb(argb: Int, withAlpha: Boolean = false): String {
        val rgb = String.format("%06X", argb and 0xFFFFFF)
        val a = (argb ushr 24) and 0xFF
        return if (withAlpha && a != 255) "#$rgb" + String.format("%02X", a) else "#$rgb"
    }

    /** Adds [hex] to the front of the recents, once, capped. */
    fun pushRecent(recents: List<String>, hex: String, max: Int = 8): List<String> {
        val n = normalize(hex) ?: return recents
        return (listOf(n) + recents.filter { it != n }).take(max)
    }
}

/** Date and time text for the date widget. Stored values are ISO-8601 strings with an offset. */
object DateTimeModel {
    data class Parts(val epochDay: Long, val hour: Int, val minute: Int, val zone: ZoneId)

    fun parse(text: String, mode: DateTimeMode, defaultZone: ZoneId = ZoneOffset.UTC): Parts? {
        val t = text.trim()
        if (t.isEmpty() || t.contains("{{")) return null
        return runCatching {
            when (mode) {
                DateTimeMode.Date -> Parts(LocalDate.parse(t.take(10)).toEpochDay(), 0, 0, defaultZone)
                DateTimeMode.Time -> LocalTime.parse(t).let { Parts(0, it.hour, it.minute, defaultZone) }
                DateTimeMode.DateTime -> {
                    val odt = runCatching { OffsetDateTime.parse(t) }.getOrNull()
                    if (odt != null) Parts(odt.toLocalDate().toEpochDay(), odt.hour, odt.minute, odt.offset)
                    else LocalDateTime.parse(t).let { Parts(it.toLocalDate().toEpochDay(), it.hour, it.minute, defaultZone) }
                }
            }
        }.getOrNull()
    }

    fun compose(epochDay: Long, hour: Int, minute: Int, mode: DateTimeMode, zone: ZoneId): String = when (mode) {
        DateTimeMode.Date -> LocalDate.ofEpochDay(epochDay).toString()
        DateTimeMode.Time -> LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).toString()
        DateTimeMode.DateTime -> LocalDate.ofEpochDay(epochDay).atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            .atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /** Millis at UTC midnight of [epochDay], the form the Material date picker uses. */
    fun pickerMillis(epochDay: Long): Long = epochDay * 86_400_000L
    fun epochDayOf(pickerMillis: Long): Long = Math.floorDiv(pickerMillis, 86_400_000L)

    val relativeUnits: List<String> = listOf("minutes", "hours", "days", "weeks", "months")

    /** "Now plus or minus N units" as an expression the evaluator understands. */
    fun relativeExpression(amount: Int, unit: String): String {
        val u = if (unit in relativeUnits) unit else "days"
        return if (amount == 0) "{{ \$now }}" else if (amount > 0) "{{ \$now.plus($amount, \"$u\") }}" else "{{ \$now.minus(${-amount}, \"$u\") }}"
    }

    fun today(zone: ZoneId, now: Instant = Instant.now()): Long = now.atZone(zone).toLocalDate().toEpochDay()
}

/** `**bold**` inside notice text. Anything else is plain; an unmatched marker is kept as written. */
object NoticeMarkup {
    data class Piece(val text: String, val bold: Boolean)

    fun pieces(text: String): List<Piece> {
        val out = ArrayList<Piece>()
        var i = 0
        var bold = false
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += Piece(sb.toString(), bold); sb.clear() } }
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (bold || close > 0) { flush(); bold = !bold; i += 2; continue }
            }
            sb.append(text[i]); i++
        }
        flush()
        return out
    }
}
