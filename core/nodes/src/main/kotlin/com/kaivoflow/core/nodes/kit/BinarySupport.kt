package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.BinaryDataRef
import java.util.Base64

/**
 * Pure-Kotlin helpers for binary attachments.
 *
 * No Android types here: the JVM unit tests and the on-device gateway share exactly the same
 * base64, MIME and file-name rules. The platform layer only decides *where* the bytes are stored.
 */
object BinarySupport {

    /** A single attachment may be at most 25 MB, matching the HTTP gateway's download cap. */
    const val MAX_BYTES = 25L * 1024 * 1024

    /** Hard cap on base64 text held in memory by the expression/pure-JVM path. */
    const val MAX_BASE64_CHARS = 36_000_000

    private val MIME_BY_EXTENSION = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv",
        "json" to "application/json", "xml" to "application/xml", "html" to "text/html",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav",
        "ogg" to "audio/ogg", "mp4" to "video/mp4", "zip" to "application/zip",
        "gz" to "application/gzip", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    private val EXTENSION_BY_MIME = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
        "image/webp" to "webp", "application/pdf" to "pdf", "text/plain" to "txt",
        "text/csv" to "csv", "application/json" to "json", "application/xml" to "xml",
        "text/html" to "html", "audio/mpeg" to "mp3", "audio/wav" to "wav",
        "video/mp4" to "mp4", "application/zip" to "zip",
    )

    fun decode(base64: String): ByteArray? {
        val cleaned = base64.trim().substringAfter("base64,", base64.trim())
        if (cleaned.length > MAX_BASE64_CHARS) return null
        return runCatching { Base64.getDecoder().decode(cleaned.replace("\\s".toRegex(), "")) }
            .getOrNull()
            ?.takeIf { it.size <= MAX_BYTES }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun mimeTypeFor(fileName: String, fallback: String = "application/octet-stream"): String =
        MIME_BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: fallback

    fun extensionFor(mimeType: String, fallback: String = "bin"): String =
        EXTENSION_BY_MIME[mimeType.substringBefore(';').trim().lowercase()] ?: fallback

    /** Removes path traversal and control characters from a user-supplied file name. */
    fun safeFileName(name: String, fallback: String = "attachment.bin"): String {
        val cleaned = name.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
        return cleaned.take(120).ifBlank { fallback }
    }

    fun withExtension(name: String, mimeType: String): String =
        if (name.contains('.')) name else "$name.${extensionFor(mimeType)}"

    /** Builds a ref directly from a map returned by a gateway (or read from storage). */
    fun ref(map: Map<String, Any?>): BinaryDataRef? = BinaryDataRef.fromJson(map)

    /**
     * The pure-JVM fallback store: a bounded `data:` URI keeps the bytes with the ref so tests and
     * the built-in expression sandbox work without a filesystem. Android overrides this with a
     * real internal file.
     */
    fun storeAsDataUri(fileName: String, mimeType: String, base64: String): Map<String, Any?>? {
        val bytes = decode(base64) ?: return null
        val safe = safeFileName(fileName)
        return mapOf(
            "uri" to "data:${mimeType.ifBlank { "application/octet-stream" }};base64,$base64",
            "mimeType" to mimeType.ifBlank { "application/octet-stream" },
            "fileName" to safe,
            "sizeBytes" to bytes.size.toLong(),
            "checksum" to CryptoMath.sha256Hex(bytes),
        )
    }

    /** Reads a `data:` URI produced by [storeAsDataUri]. Any other scheme is a platform concern. */
    fun readDataUri(uri: String): String? {
        if (!uri.startsWith("data:")) return null
        val marker = ";base64,"
        val index = uri.indexOf(marker)
        if (index < 0) return null
        val payload = uri.substring(index + marker.length)
        return if (decode(payload) != null) payload else null
    }
}
