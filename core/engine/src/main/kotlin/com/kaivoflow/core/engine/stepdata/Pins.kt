package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import kotlinx.coroutines.flow.Flow

enum class PinSource { Execution, Json }

/** Explicit user data that replaces a Step's output in manual Runs. [ports] holds one item list per output port. */
data class PinnedData(
    val ports: List<List<Item>>,
    val bytes: Long,
    val pinnedAt: Long,
    val source: PinSource,
)

sealed interface PinResult {
    data class Ok(val data: PinnedData) : PinResult
    data class TooLarge(val bytes: Long, val limit: Long) : PinResult
    data class Invalid(val message: String, val line: Int?, val column: Int?) : PinResult

    /** The source execution kept only part of its data (or none); pinning would silently lose items. */
    data object Truncated : PinResult
}

/** What the engine needs to replay pins. */
fun interface PinReader {
    suspend fun pinnedFor(flowId: String, stepId: String): PinnedData?
}

interface PinStore : PinReader {
    fun observe(flowId: String, stepId: String): Flow<PinnedData?>

    /** Pins output [port] of a stored execution; other ports stay empty. Refuses truncated sources. */
    suspend fun pinFromExecution(flowId: String, key: StepExecutionKey, port: Int): PinResult
    suspend fun pinFromJson(flowId: String, stepId: String, json: String): PinResult
    suspend fun unpin(flowId: String, stepId: String)

    /** Every pin of a Flow keyed by Step id (export). */
    suspend fun pinsOf(flowId: String): Map<String, PinnedData>

    /** Writes already validated pins (import). Caps are enforced again; returns the ids that were refused. */
    suspend fun restore(flowId: String, pins: Map<String, PinnedData>): List<String>
}

/** Where pins live (Room in the app, a map in tests). */
interface PinPersistence {
    suspend fun load(flowId: String, stepId: String): PinnedData?
    suspend fun save(flowId: String, stepId: String, data: PinnedData)
    suspend fun delete(flowId: String, stepId: String)
    suspend fun all(flowId: String): Map<String, PinnedData>
    fun observe(flowId: String, stepId: String): Flow<PinnedData?>
}

/** Pin rules (caps, validation, truncation refusal) on top of any [PinPersistence]. */
class PinService(
    private val persistence: PinPersistence,
    private val executions: StepDataStore?,
    private val policy: StepDataPolicy = StepDataPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) : PinStore {

    override fun observe(flowId: String, stepId: String): Flow<PinnedData?> = persistence.observe(flowId, stepId)

    override suspend fun pinnedFor(flowId: String, stepId: String): PinnedData? = persistence.load(flowId, stepId)

    override suspend fun pinFromExecution(flowId: String, key: StepExecutionKey, port: Int): PinResult {
        val store = executions ?: return PinResult.Invalid("Step data is not available.", null, null)
        val summary = store.summary(key) ?: return PinResult.Invalid("That execution is no longer stored.", null, null)
        val snap = summary.outputs.firstOrNull { it.port == port }
            ?: return PinResult.Invalid("That execution has no output on port ${port + 1}.", null, null)
        if (summary.dataDropped || snap.truncation != null || snap.storedCount < snap.itemCount) return PinResult.Truncated
        val items = if (snap.storedCount == 0) emptyList() else store.page(key, Direction.Out, port, 0, snap.storedCount).items
        val ports = List(port + 1) { if (it == port) items else emptyList() }
        return accept(flowId, key.stepId, ports, PinSource.Execution)
    }

    override suspend fun pinFromJson(flowId: String, stepId: String, json: String): PinResult =
        when (val parsed = PinJson.items(json)) {
            is PinJson.Parsed.Error -> PinResult.Invalid(parsed.message, parsed.line, parsed.column)
            is PinJson.Parsed.Items -> accept(flowId, stepId, listOf(parsed.items), PinSource.Json)
        }

    override suspend fun unpin(flowId: String, stepId: String) = persistence.delete(flowId, stepId)

    override suspend fun pinsOf(flowId: String): Map<String, PinnedData> = persistence.all(flowId)

    override suspend fun restore(flowId: String, pins: Map<String, PinnedData>): List<String> {
        val refused = ArrayList<String>()
        for ((stepId, data) in pins) {
            val r = accept(flowId, stepId, data.ports, data.source)
            if (r !is PinResult.Ok) refused += stepId
        }
        return refused
    }

    private suspend fun accept(flowId: String, stepId: String, ports: List<List<Item>>, source: PinSource): PinResult {
        val bytes = ports.sumOf { p -> p.sumOf { policy.sizeOf(it) } }
        val others = persistence.all(flowId).filterKeys { it != stepId }.values.sumOf { it.bytes }
        when (val v = policy.pinVerdict(bytes, others)) {
            PinVerdict.Ok -> {}
            is PinVerdict.StepTooLarge -> return PinResult.TooLarge(v.bytes, v.limit)
            is PinVerdict.FlowTooLarge -> return PinResult.TooLarge(v.bytes, v.limit)
        }
        val data = PinnedData(ports, bytes, clock(), source)
        persistence.save(flowId, stepId, data)
        return PinResult.Ok(data)
    }
}

/**
 * Strict JSON reader for pin input. Reports the line and column (both 1-based) of the first problem.
 * Accepted shapes: an array of objects, one object, or either of them with each item wrapped as
 * `{"json": {...}}`. Anything else is refused with the position of the offending element.
 */
object PinJson {
    sealed interface Parsed {
        data class Items(val items: List<Item>) : Parsed
        data class Error(val message: String, val line: Int, val column: Int) : Parsed
    }

    private const val MAX_DEPTH = 64

    private class Fail(val pos: Int, message: String) : RuntimeException(message)

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(msg: String, at: Int = i): Nothing = throw Fail(at, msg)

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("Nested too deeply.")
            ws()
            if (i >= s.length) fail("Unexpected end of input.")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Unexpected character '$c'.")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Unexpected character '${s[i]}'.")
            i += word.length
            return v
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("Invalid number '$t'.", start)
        }

        fun str(): String {
            val start = i
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string.", start)
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("Unterminated string.", start)
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                if (hex.length < 4 || hex.any { ch -> ch.digitToIntOrNull(16) == null }) fail("Invalid \\u escape.", i - 2)
                                sb.append(hex.toInt(16).toChar()); i += 4
                            }
                            else -> fail("Invalid escape '\\$e'.", i - 2)
                        }
                    }
                    c < ' ' -> fail("Control character in string.", i - 1)
                    else -> sb.append(c)
                }
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected a property name in quotes.")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("Expected ':' after the property name.")
                i++
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail("Unexpected end of input.")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("Expected ',' or '}'.")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                ws()
                if (i >= s.length) fail("Unexpected end of input.")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("Expected ',' or ']'.")
                }
            }
        }
    }

    fun items(text: String): Parsed {
        fun pos(at: Int): Pair<Int, Int> {
            var line = 1; var col = 1
            for (k in 0 until minOf(at, text.length)) if (text[k] == '\n') { line++; col = 1 } else col++
            return line to col
        }
        fun err(msg: String, at: Int): Parsed.Error = pos(at).let { Parsed.Error(msg, it.first, it.second) }
        if (text.isBlank()) return err("Nothing to pin: the text is empty.", 0)
        val r = Reader(text)
        val starts = ArrayList<Int>()
        val root: Any? = try {
            r.ws()
            if (r.i < text.length && text[r.i] == '[') {
                // Parse the top-level array by hand so each element's start position is known.
                r.i++
                val out = ArrayList<Any?>()
                r.ws()
                if (r.i < text.length && text[r.i] == ']') r.i++ else while (true) {
                    r.ws(); starts += r.i
                    out += r.value(1)
                    r.ws()
                    if (r.i >= text.length) r.fail("Unexpected end of input.")
                    if (text[r.i] == ',') r.i++ else if (text[r.i] == ']') { r.i++; break } else r.fail("Expected ',' or ']'.")
                }
                out
            } else r.value(0)
        } catch (f: Fail) {
            return err(f.message ?: "Invalid JSON.", f.pos)
        }
        r.ws()
        if (r.i < text.length) return err("Unexpected text after the JSON value.", r.i)
        val elements: List<Any?> = if (root is List<*>) root else listOf(root)
        if (elements.isEmpty()) return err("Nothing to pin: the array is empty.", 0)
        val items = ArrayList<Item>(elements.size)
        for ((n, e) in elements.withIndex()) {
            val at = starts.getOrElse(n) { 0 }
            @Suppress("UNCHECKED_CAST")
            val m = e as? Map<String, Any?> ?: return err("Item ${n + 1} must be an object.", at)
            val inner = m["json"]
            @Suppress("UNCHECKED_CAST")
            items += if (inner is Map<*, *> && m.keys.all { it == "json" || it == "binary" }) Item(json = inner as Map<String, Any?>) else Item(json = m)
        }
        return Parsed.Items(items)
    }
}
