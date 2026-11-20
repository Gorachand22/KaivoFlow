package com.kaivoflow.core.ai

/**
 * Structured output through a tool call, the way n8n does it: the output schema becomes the parameters of a hidden
 * tool, so the model's native tool calling does the formatting and the provider validates the shape. Text parsing
 * with repair ([StructuredOutputParser]) stays as the fallback for models that answer in text anyway.
 * A schema that is not an object (an array, for example) is wrapped as `{"output": <schema>}` because providers
 * need object parameters; [unwrap] takes it out again.
 */
object FinalAnswer {
    const val TOOL_NAME = "format_final_answer"

    private const val DESCRIPTION =
        "Use this tool to give your final answer in the required structured format. Call it once, only when you are " +
            "finished and have everything you need, and call it alone. The arguments are checked against a schema; " +
            "if they are rejected, fix them and call it again."

    private fun isObjectSchema(schema: Map<String, Any?>) = schema["type"] == "object" || schema.containsKey("properties")

    fun spec(schema: Map<String, Any?>): ToolSpec = ToolSpec(
        TOOL_NAME, DESCRIPTION,
        if (isObjectSchema(schema)) schema else mapOf("type" to "object", "properties" to mapOf("output" to schema), "required" to listOf("output"), "additionalProperties" to false),
    )

    fun unwrap(schema: Map<String, Any?>, args: Map<String, Any?>): Any? = if (isObjectSchema(schema)) args else args["output"]

    /** Problems with [args] against [schema]; empty means the answer is valid. */
    fun validate(schema: Map<String, Any?>, args: Map<String, Any?>): List<String> = SchemaCheck.validate(schema, unwrap(schema, args))
}

/**
 * Reads a list from text the way n8n's item list parser does: split by a separator (a new line by default), trim,
 * drop empty entries. Added here: bullets and numbering ("- ", "1. ") are removed when splitting by lines. Too few
 * items is an error, too many are cut to [count].
 */
object ItemListParser {
    private val LEADER = Regex("^\\s*(?:[-*\\u2022]|[0-9]{1,3}[.)])\\s+")

    fun parse(text: String, separator: String = "\n", count: Int? = null): ParseResult {
        val sep = if (separator == "\\n" || separator.isEmpty()) "\n" else separator
        val items = text.trim().split(sep).map { if (sep == "\n") it.replace(LEADER, "") else it }.map { it.trim() }.filter { it.isNotEmpty() }
        if (count != null && count > 0 && items.size < count) {
            return ParseResult.Invalid(listOf("expected $count items but got ${items.size}"))
        }
        return ParseResult.Ok(if (count != null && count > 0) items.take(count) else items)
    }

    /** A structured parser for the agent loop: schema = array of strings; JSON arrays are accepted, else the text is split. */
    fun structured(separator: String = "\n", count: Int? = null): StructuredOutputParser {
        val schema = buildMap<String, Any?> {
            put("type", "array"); put("items", mapOf("type" to "string"))
            if (count != null && count > 0) { put("minItems", count); put("maxItems", count) }
        }
        return StructuredOutputParser(schema, textReader = { parse(it, separator, count) })
    }

    fun instructions(separator: String = "\n", count: Int? = null): String {
        val sepText = if (separator == "\n" || separator == "\\n") "on separate lines" else "separated by \"$separator\""
        return "Your answer is a list of ${count?.let { "$it " } ?: ""}items $sepText."
    }
}
