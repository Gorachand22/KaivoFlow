package com.kaivoflow.core.data

import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.engine.stepdata.ItemFilter
import com.kaivoflow.core.engine.stepdata.ParentRef
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.engine.stepdata.Truncation
import com.kaivoflow.core.engine.stepdata.TruncationReason
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaOrigin
import com.kaivoflow.core.model.SchemaType
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** An item read back from a port: its position in the stored list, its position in the untruncated list, and the item. */
data class StoredItem(val index: Int, val original: Int, val item: Item)

/**
 * The stored form of one port's items (spec 5.2): gzip blocks of [BLOCK_SIZE] items, each block
 * newline-delimited item JSON, plus an offsets table so a page decompresses only the blocks it needs.
 * Pure Kotlin; no Android or Room classes.
 *
 * [blocksRead] counts decompressed blocks (tests assert the bound on a page read).
 */
class PortBlob(val items: ByteArray, val offsets: ByteArray) {
    private val table: IntArray = StepDataCodec.readVarints(offsets)
    val itemCount: Int = if (table.isEmpty()) 0 else table[0]
    val blockCount: Int = maxOf(table.size - 1, 0)
    private val starts: IntArray = IntArray(blockCount + 1).also { for (b in 0 until blockCount) it[b + 1] = it[b] + table[b + 1] }
    private val reads = AtomicInteger(0)

    val blocksRead: Int get() = reads.get()

    private fun block(b: Int): List<String> {
        reads.incrementAndGet()
        val raw = GZIPInputStream(ByteArrayInputStream(items, starts[b], starts[b + 1] - starts[b])).use { it.readBytes() }
        return if (raw.isEmpty()) emptyList() else String(raw, Charsets.UTF_8).split('\n')
    }

    /** Items at stored positions [from, from + count). Reads only the blocks that cover the range. */
    fun range(from: Int, count: Int): List<StoredItem> {
        if (count <= 0 || from >= itemCount || from < 0) return emptyList()
        val end = minOf(itemCount, from + count)
        val out = ArrayList<StoredItem>(end - from)
        for (b in (from / StepDataCodec.BLOCK_SIZE)..((end - 1) / StepDataCodec.BLOCK_SIZE)) {
            val lines = block(b)
            val base = b * StepDataCodec.BLOCK_SIZE
            for ((k, line) in lines.withIndex()) {
                val idx = base + k
                if (idx in from until end) out += StepDataCodec.decodeLine(idx, line)
            }
        }
        return out
    }

    /** Reads everything once and keeps the items that satisfy [filter]. */
    fun matching(filter: ItemFilter): List<StoredItem> {
        val q = filter.query?.takeIf { it.isNotEmpty() }?.lowercase()
        val out = ArrayList<StoredItem>()
        for (b in 0 until blockCount) {
            val base = b * StepDataCodec.BLOCK_SIZE
            for ((k, line) in block(b).withIndex()) {
                val stored = StepDataCodec.decodeLine(base + k, line)
                if (filter.onlyErrors && stored.item.error == null) continue
                if (q != null && !StepDataCodec.searchText(stored.item).contains(q)) continue
                out += stored
            }
        }
        return out
    }
}

object StepDataCodec {
    const val BLOCK_SIZE = 64
    private val json = Json { ignoreUnknownKeys = true }

    // ---- varints ----

    fun writeVarints(values: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (v in values) {
            var x = v.toLong() and 0xFFFFFFFFL
            while (x >= 0x80) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        return out.toByteArray()
    }

    fun readVarints(bytes: ByteArray): IntArray {
        val out = ArrayList<Int>()
        var x = 0L
        var shift = 0
        for (b in bytes) {
            x = x or ((b.toLong() and 0x7F) shl shift)
            if (b.toInt() and 0x80 == 0) { out += x.toInt(); x = 0; shift = 0 } else shift += 7
        }
        return out.toIntArray()
    }

    // ---- items ----

    /** [originalIndices] has one entry per item (position in the untruncated list); a missing entry means "same as stored index". */
    fun encodeItems(items: List<Item>, originalIndices: List<Int> = emptyList()): PortBlob {
        val blocks = ArrayList<ByteArray>()
        var i = 0
        while (i < items.size) {
            val end = minOf(items.size, i + BLOCK_SIZE)
            val text = (i until end).joinToString("\n") { n -> encodeLine(items[n], originalIndices.getOrElse(n) { n }) }
            val buf = ByteArrayOutputStream()
            GZIPOutputStream(buf).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            blocks += buf.toByteArray()
            i = end
        }
        val table = IntArray(blocks.size + 1)
        table[0] = items.size
        blocks.forEachIndexed { b, bytes -> table[b + 1] = bytes.size }
        val all = ByteArrayOutputStream()
        blocks.forEach { all.write(it) }
        return PortBlob(all.toByteArray(), writeVarints(table))
    }

    private fun encodeLine(item: Item, original: Int): String {
        val base = JsonCodec.encodeFlowData(item)
        return JsonObject(base + ("o" to JsonPrimitive(original))).toString()
    }

    fun decodeLine(index: Int, line: String): StoredItem {
        val obj = json.parseToJsonElement(line) as JsonObject
        val original = (obj["o"] as? JsonPrimitive)?.intOrNull ?: index
        return StoredItem(index, original, JsonCodec.decodeFlowData(obj))
    }

    /** Lower-cased text a search matches against: the item's data and its error message. */
    fun searchText(item: Item): String =
        (Values.stringify(item.json) + (item.error?.message?.let { " $it" } ?: "")).lowercase()

    // ---- small JSON columns ----

    fun truncationToJson(t: Truncation?): String? = t?.let {
        buildJsonObject {
            put("reason", JsonPrimitive(it.reason.name))
            put("kept", JsonPrimitive(it.keptItems))
            put("original", JsonPrimitive(it.originalItems))
            put("clipped", JsonPrimitive(it.clippedValues))
            put("errors", JsonPrimitive(it.errorItemsKept))
        }.toString()
    }

    fun truncationFromJson(text: String?): Truncation? = parse(text)?.let { o ->
        Truncation(
            reason = runCatching { TruncationReason.valueOf(o.str("reason")) }.getOrDefault(TruncationReason.ItemCap),
            keptItems = o.int("kept"), originalItems = o.int("original"), clippedValues = o.int("clipped"), errorItemsKept = o.int("errors"),
        )
    }

    fun parentsToJson(parents: List<ParentRef>): String = buildJsonArray {
        parents.forEach { add(buildJsonObject { put("step", JsonPrimitive(it.stepId)); put("port", JsonPrimitive(it.port)) }) }
    }.toString()

    fun parentsFromJson(text: String?): List<ParentRef> =
        (runCatching { text?.let { json.parseToJsonElement(it) } }.getOrNull() as? JsonArray)?.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            ParentRef(o.str("step"), o.int("port"))
        }.orEmpty()

    fun errorToJson(e: StepErrorInfo?): String? = e?.let {
        buildJsonObject {
            put("message", JsonPrimitive(it.message))
            it.itemIndex?.let { n -> put("itemIndex", JsonPrimitive(n)) }
            put("kind", JsonPrimitive(it.kind))
            it.details?.let { d -> put("details", JsonPrimitive(d)) }
            it.httpStatus?.let { s -> put("httpStatus", JsonPrimitive(s)) }
            it.httpBody?.let { b -> put("httpBody", JsonPrimitive(b)) }
        }.toString()
    }

    fun errorFromJson(text: String?): StepErrorInfo? = parse(text)?.let { o ->
        StepErrorInfo(
            message = o.str("message"), itemIndex = (o["itemIndex"] as? JsonPrimitive)?.intOrNull, kind = o.str("kind").ifEmpty { "step" },
            details = (o["details"] as? JsonPrimitive)?.contentOrNull, httpStatus = (o["httpStatus"] as? JsonPrimitive)?.intOrNull,
            httpBody = (o["httpBody"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    // ---- schema ----

    fun schemaToJson(n: SchemaNode?): String? = n?.let { schemaElement(it).toString() }

    private fun schemaElement(n: SchemaNode): JsonObject = buildJsonObject {
        put("t", JsonPrimitive(n.type.name))
        put("s", JsonPrimitive(n.seenIn))
        put("of", JsonPrimitive(n.of))
        put("ob", JsonPrimitive(n.objects))
        if (n.truncated) put("tr", JsonPrimitive(true))
        if (n.origin != SchemaOrigin.Observed) put("or", JsonPrimitive(n.origin.name))
        n.sample?.let { put("sa", JsonPrimitive(it)) }
        if (n.mixed.isNotEmpty()) put("mx", buildJsonArray { n.mixed.forEach { add(JsonPrimitive(it.name)) } })
        if (n.fields.isNotEmpty()) put("f", JsonArray(n.fields.entries.map { (k, v) -> buildJsonArray { add(JsonPrimitive(k)); add(schemaElement(v)) } }))
        n.element?.let { put("e", schemaElement(it)) }
    }

    fun schemaFromJson(text: String?): SchemaNode? = parse(text)?.let { runCatching { schemaNode(it) }.getOrNull() }

    private fun schemaNode(o: JsonObject): SchemaNode = SchemaNode(
        type = runCatching { SchemaType.valueOf(o.str("t")) }.getOrDefault(SchemaType.Mixed),
        fields = ((o["f"] as? JsonArray)?.associate { pair ->
            val a = pair as JsonArray
            (a[0] as JsonPrimitive).content to schemaNode(a[1] as JsonObject)
        } ?: emptyMap()).toImmutableMap(),
        element = (o["e"] as? JsonObject)?.let { schemaNode(it) },
        seenIn = o.int("s"), of = o.int("of"),
        sample = (o["sa"] as? JsonPrimitive)?.contentOrNull,
        mixed = ((o["mx"] as? JsonArray)?.mapNotNull { e -> runCatching { SchemaType.valueOf((e as JsonPrimitive).content) }.getOrNull() }
            ?: emptyList()).toImmutableSet(),
        origin = runCatching { SchemaOrigin.valueOf(o.str("or")) }.getOrDefault(SchemaOrigin.Observed),
        objects = o.int("ob"),
        truncated = (o["tr"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )

    // ---- helpers ----

    private fun parse(text: String?): JsonObject? =
        if (text.isNullOrEmpty()) null else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() } ?: 0
}
