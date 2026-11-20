package com.kaivoflow.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class StreamLineDecoderTest {
    private fun lines(chunks: List<ByteArray>, max: Int = StreamLineDecoder.DEFAULT_MAX_LINE_BYTES): List<String> {
        val d = StreamLineDecoder(max)
        val out = ArrayList<String>()
        chunks.forEach { out += d.feed(it) }
        d.finish()?.let { out += it }
        return out
    }

    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test fun `splits on lf, crlf and lone cr`() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines(listOf(b("a\nb\r\nc\r\r\nd\n"))))
    }

    @Test fun `a crlf split between two chunks is one terminator`() {
        assertEquals(listOf("data: x", "", "data: y"), lines(listOf(b("data: x\r"), b("\n\r"), b("\ndata: y\n"))))
    }

    @Test fun `a line split over many chunks arrives whole and only when complete`() {
        val d = StreamLineDecoder()
        assertEquals(emptyList<String>(), d.feed(b("data: {\"a\"")))
        assertEquals(emptyList<String>(), d.feed(b(":1")))
        assertEquals(listOf("data: {\"a\":1}"), d.feed(b("}\nrest")))
        assertEquals("rest", d.finish())
        assertNull(d.finish())
    }

    @Test fun `a multi byte character split across chunks is not damaged`() {
        val bytes = b("data: héllo 世界 😀\n")
        // one byte per chunk is the harshest split
        val chunks = bytes.map { byteArrayOf(it) }
        assertEquals(listOf("data: héllo 世界 😀"), lines(chunks))
    }

    @Test fun `invalid utf8 becomes a replacement character and does not fail`() {
        val r = lines(listOf(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), '\n'.code.toByte())))
        assertEquals(listOf("a�"), r)
    }

    @Test fun `a byte order mark at the start is dropped once`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(listOf("data: 1", "data: 2"), lines(listOf(bom + b("data: 1\n"), b("data: 2\n"))))
    }

    @Test fun `last line without a terminator is returned at the end`() {
        assertEquals(listOf("one", "two"), lines(listOf(b("one\ntw"), b("o"))))
    }

    @Test fun `an empty body gives no lines`() {
        assertEquals(emptyList<String>(), lines(emptyList()))
    }

    @Test fun `a line over the limit is an error and not buffered without end`() {
        try {
            lines(listOf(b("x".repeat(40))), max = 32)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("longer than 32"))
        }
    }

    @Test fun `feed honours offset and count`() {
        val d = StreamLineDecoder()
        val src = b("XXab\ncdYY")
        assertEquals(listOf("ab"), d.feed(src, 2, 3))
        assertEquals(emptyList<String>(), d.feed(src, 5, 2))
        assertEquals("cd", d.finish())
    }

    @Test fun `readStreamLines delivers each line as soon as its chunk arrives`() = runBlocking {
        val chunks = ArrayDeque(listOf(b("data: 1\n\nda"), b("ta: 2\n"), b("\n")))
        val seen = ArrayList<String>()
        var readsWhenFirstSeen = -1
        var reads = 0
        readStreamLines(read = { buf, off, len ->
            reads++
            val c = chunks.removeFirstOrNull() ?: return@readStreamLines -1
            System.arraycopy(c, 0, buf, off, minOf(len, c.size)); c.size
        }) { line -> if (seen.isEmpty()) readsWhenFirstSeen = reads; seen += line }
        assertEquals(listOf("data: 1", "", "data: 2", ""), seen)
        assertEquals("the first line must not wait for the end of the body", 1, readsWhenFirstSeen)
    }

    @Test fun `a failing read surfaces after the lines before it`() = runBlocking {
        val seen = ArrayList<String>()
        var n = 0
        try {
            readStreamLines(read = { buf, off, _ ->
                if (n++ == 0) { val c = b("a\nb"); System.arraycopy(c, 0, buf, off, c.size); c.size } else throw IOException("reset")
            }) { seen += it }
            fail("expected")
        } catch (e: IOException) {
            assertEquals("reset", e.message)
        }
        assertEquals(listOf("a"), seen)
    }
}
