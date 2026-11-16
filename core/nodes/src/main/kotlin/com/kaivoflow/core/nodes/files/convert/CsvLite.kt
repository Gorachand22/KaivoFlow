package com.kaivoflow.core.nodes.files.convert

import com.kaivoflow.core.engine.Values

// ---- Pure format engines ---------------------------------------------------------------

internal object CsvLite {
    fun parse(text: String, delimiter: Char, hasHeader: Boolean): List<Map<String, Any?>> {
        if (text.length > 2_000_000) return emptyList()
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        val row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
                c == '"' && field.isEmpty() -> quoted = true
                c == delimiter -> { row += field.toString(); field = StringBuilder() }
                c == '\n' -> {
                    row += field.toString().trimEnd('\r')
                    field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString().trimEnd('\r')
        if (row.any { it.isNotEmpty() }) rows += row.toList()
        if (rows.isEmpty()) return emptyList()
        val headers = if (hasHeader) {
            rows.first().mapIndexed { index, name -> name.trim().ifBlank { "column_$index" } }
        } else {
            val width = rows.maxOf { it.size }
            (0 until width).map { "column_$it" }
        }
        val body = if (hasHeader) rows.drop(1) else rows
        return body.map { values ->
            headers.mapIndexed { index, name -> name to (values.getOrNull(index) ?: "") }.toMap()
        }
    }

    fun build(rows: List<Map<String, Any?>>, columns: List<String>, delimiter: Char): String {
        val headers = columns.ifEmpty {
            rows.flatMap { it.keys }.distinct().map { it.toString() }
        }
        val sb = StringBuilder()
        sb.append(headers.joinToString(delimiter.toString()) { escape(it) }).append('\n')
        rows.forEach { row ->
            sb.append(headers.joinToString(delimiter.toString()) { key ->
                escape(Values.asString(row[key]))
            }).append('\n')
        }
        return sb.toString()
    }

    private fun escape(value: String): String {
        val needs = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needs) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
