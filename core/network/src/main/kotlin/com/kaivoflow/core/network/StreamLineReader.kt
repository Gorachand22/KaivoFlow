package com.kaivoflow.core.network

import java.io.IOException

/**
 * Turns the bytes of a streamed response body into text lines, as they arrive. Pure Kotlin so it is tested without a
 * network.
 *
 * Rules (the ones SSE and NDJSON both need): a line ends at LF, CR LF or a lone CR, and comes back without its
 * terminator; a CR at the end of one chunk followed by LF at the start of the next is one terminator; a UTF-8 byte order
 * mark at the very start is dropped; the line is decoded only when it is complete, so a multi-byte character split
 * across chunks is never damaged (LF and CR never occur inside a multi-byte UTF-8 sequence); bytes that are not valid
 * UTF-8 become U+FFFD instead of failing; a line longer than [maxLineBytes] is an error, so a server cannot make the
 * phone buffer without limit. A last line without a terminator is returned by [finish].
 */
class StreamLineDecoder(private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES) {
    private var buf = ByteArray(1024)
    private var len = 0
    private var pendingCr = false
    private var atStart = true

    /** Feeds [count] bytes of [bytes] from [offset]; returns the lines they completed, in order. */
    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset): List<String> {
        var out: ArrayList<String>? = null
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = bytes[i++]
            if (pendingCr) {
                pendingCr = false
                if (b == LF) continue // second half of CR LF
            }
            when (b) {
                LF, CR -> {
                    if (b == CR) pendingCr = true
                    (out ?: ArrayList<String>().also { out = it }).add(take())
                }
                else -> append(b)
            }
        }
        return out ?: emptyList()
    }

    /** At the end of the body: the unterminated last line, or null when there is none. */
    fun finish(): String? {
        pendingCr = false
        return if (len == 0) null else take()
    }

    private fun append(b: Byte) {
        if (len >= maxLineBytes) throw IOException("The stream sent a line longer than $maxLineBytes bytes.")
        if (len == buf.size) buf = buf.copyOf(minOf(buf.size * 2, maxLineBytes.coerceAtLeast(16)))
        buf[len++] = b
    }

    private fun take(): String {
        var from = 0
        if (atStart) {
            atStart = false
            if (len >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) from = 3
        }
        val s = String(buf, from, len - from, Charsets.UTF_8)
        len = 0
        return s
    }

    companion object {
        const val DEFAULT_MAX_LINE_BYTES = 1 shl 20
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}

/**
 * Reads a body through [read] (returns the number of bytes put in the buffer, or -1 at the end) and gives each line to
 * [emit] the moment it is complete. Nothing is logged: lines can carry model output and must not reach a log.
 */
suspend fun readStreamLines(
    read: suspend (ByteArray, Int, Int) -> Int,
    maxLineBytes: Int = StreamLineDecoder.DEFAULT_MAX_LINE_BYTES,
    emit: suspend (String) -> Unit,
) {
    val decoder = StreamLineDecoder(maxLineBytes)
    val chunk = ByteArray(8 * 1024)
    while (true) {
        val n = read(chunk, 0, chunk.size)
        if (n < 0) break
        if (n == 0) continue
        for (line in decoder.feed(chunk, 0, n)) emit(line)
    }
    decoder.finish()?.let { emit(it) }
}
