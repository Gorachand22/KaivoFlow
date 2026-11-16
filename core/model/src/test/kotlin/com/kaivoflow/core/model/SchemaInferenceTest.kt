package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaInferenceTest {

    private fun infer(vararg items: Item, limits: SchemaLimits = SchemaLimits.Default) =
        SchemaInference.infer(items.asSequence(), limits)

    private fun at(n: SchemaNode, vararg path: String) = SchemaInference.nodeAt(n, SchemaPath.of(*path))!!

    @Test fun `scalars and nested objects`() {
        val s = infer(Item.of("name" to "Asha", "age" to 31L, "ok" to true, "addr" to mapOf("city" to "Pune", "geo" to mapOf("lat" to 1.5))))
        assertEquals(SchemaType.Object, s.type)
        assertEquals(SchemaType.String, at(s, "name").type)
        assertEquals(SchemaType.Number, at(s, "age").type)
        assertEquals(SchemaType.Bool, at(s, "ok").type)
        assertEquals(SchemaType.Number, at(s, "addr", "geo", "lat").type)
        assertEquals("Asha", at(s, "name").sample)
        assertEquals(1, s.seenIn)
        assertEquals(1, s.of)
    }

    @Test fun `array of objects keeps element shape`() {
        val s = infer(Item.of("rows" to listOf(mapOf("id" to 1L, "tag" to "a"), mapOf("id" to 2L))))
        val rows = at(s, "rows")
        assertEquals(SchemaType.Array, rows.type)
        val el = rows.element!!
        assertEquals(SchemaType.Object, el.type)
        assertEquals(SchemaType.Number, el.fields["id"]!!.type)
        assertEquals(1, el.fields["tag"]!!.seenIn)
        assertEquals(2, el.fields["tag"]!!.of)
        assertTrue(el.fields["tag"]!!.optional)
        assertEquals(SchemaType.Number, SchemaInference.typeAt(s, SchemaPath.of("rows", "[]", "id")))
    }

    @Test fun `optional fields count presence across items`() {
        val items = (1..12).map { i -> if (i <= 7) Item.of("id" to i.toLong(), "note" to "n$i") else Item.of("id" to i.toLong()) }
        val s = SchemaInference.infer(items.asSequence())
        assertEquals(12, s.of)
        assertEquals(7, at(s, "note").seenIn)
        assertEquals(12, at(s, "note").of)
        assertTrue(at(s, "note").optional)
        assertFalse(at(s, "id").optional)
    }

    @Test fun `a null value is nullable but keeps its type`() {
        val s = infer(Item.of("x" to null), Item.of("x" to "a"))
        val x = at(s, "x")
        assertEquals(SchemaType.String, x.type)
        assertTrue(x.nullable)
        assertEquals(2, x.seenIn)
        assertEquals(SchemaType.Null, at(infer(Item.of("x" to null)), "x").type)
    }

    @Test fun `mixed types list what was seen`() {
        val s = infer(Item.of("v" to 1L), Item.of("v" to "a"), Item.of("v" to true))
        val v = at(s, "v")
        assertEquals(SchemaType.Mixed, v.type)
        assertEquals(setOf(SchemaType.Number, SchemaType.String, SchemaType.Bool), v.mixed.toSet())
    }

    @Test fun `iso dates are detected only when every value is one`() {
        assertEquals(SchemaType.Date, at(infer(Item.of("d" to "2026-11-16T10:00:00Z"), Item.of("d" to "2026-01-02")), "d").type)
        assertEquals(SchemaType.Date, at(infer(Item.of("d" to "2026-11-16T10:00:00+05:30")), "d").type)
        assertEquals(SchemaType.String, at(infer(Item.of("d" to "2026-11-16"), Item.of("d" to "tomorrow")), "d").type)
        assertEquals(SchemaType.String, at(infer(Item.of("d" to "2026-13-45")), "d").type)
        assertEquals(SchemaType.String, at(infer(Item.of("d" to "12345")), "d").type)
    }

    @Test fun `binary comes from the item binary map`() {
        val item = Item(
            json = mapOf("binary" to "just text"),
            binary = mapOf("photo" to BinaryDataRef("content://x/1", "image/jpeg", "p.jpg", 10)),
        )
        val s = infer(item)
        assertEquals(SchemaType.String, at(s, "binary").type)
        assertEquals(SchemaType.Binary, at(s, SchemaInference.BINARY_KEY, "photo").type)
        assertEquals(SchemaType.Object, at(s, SchemaInference.BINARY_KEY).type)
        assertTrue(at(s, SchemaInference.BINARY_KEY, "photo").sample!!.contains("image/jpeg"))
    }

    @Test fun `depth cap collapses deep nesting`() {
        var v: Any? = mapOf("leaf" to 1L)
        repeat(30) { v = mapOf("n" to v) }
        val s = infer(Item.of("root" to v), limits = SchemaLimits(maxDepth = 4))
        var cur = s
        var depth = 0
        while (cur.fields.containsKey("n") || (depth == 0 && cur.fields.containsKey("root"))) {
            cur = cur.fields["n"] ?: cur.fields["root"]!!
            depth++
        }
        assertTrue("depth $depth", depth <= 4)
        assertTrue(cur.truncated)
        assertEquals(SchemaType.Object, cur.type)
    }

    @Test fun `field cap stops adding fields`() {
        val item = Item((1..100).associate { "f$it" to it.toLong() })
        val s = infer(item, limits = SchemaLimits(maxFields = 10))
        assertEquals(10, s.fields.size)
        assertTrue(s.truncated)
        assertEquals(listOf("f1", "f2", "f3"), s.fields.keys.take(3))
    }

    @Test fun `item and array element caps`() {
        val items = (1..300).map { Item.of("a" to it.toLong()) }
        assertEquals(200, SchemaInference.infer(items.asSequence()).of)
        val s = infer(Item.of("l" to (1..50).map { it.toLong() }), limits = SchemaLimits(maxArrayElements = 20))
        assertEquals(20, at(s, "l").element!!.seenIn)
    }

    @Test fun `long samples are clipped`() {
        val s = infer(Item.of("t" to "x".repeat(500)), limits = SchemaLimits(maxSampleChars = 20))
        assertEquals(20, at(s, "t").sample!!.length)
    }

    @Test fun `merge is associative and matches one pass inference`() {
        val a = infer(Item.of("id" to 1L, "x" to "a", "o" to mapOf("p" to 1L)))
        val b = infer(Item.of("id" to 2L, "y" to null, "o" to mapOf("q" to "s"), "l" to listOf(1L)))
        val c = infer(Item.of("id" to "three", "x" to "2026-01-01", "l" to listOf("z")))
        val left = SchemaInference.merge(SchemaInference.merge(a, b), c)
        val right = SchemaInference.merge(a, SchemaInference.merge(b, c))
        assertEquals(left, right)
        val together = infer(
            Item.of("id" to 1L, "x" to "a", "o" to mapOf("p" to 1L)),
            Item.of("id" to 2L, "y" to null, "o" to mapOf("q" to "s"), "l" to listOf(1L)),
            Item.of("id" to "three", "x" to "2026-01-01", "l" to listOf("z")),
        )
        assertEquals(together, left)
        assertEquals(SchemaType.Mixed, at(left, "id").type)
        assertEquals(1, at(left, "o", "p").seenIn)
        assertEquals(2, at(left, "o", "p").of)
    }

    @Test fun `paths and declared shapes`() {
        val s = infer(Item.of("a" to mapOf("b" to 1L), "l" to listOf(mapOf("c" to 1L))))
        val paths = SchemaInference.pathsOf(s).map { it.toString() }
        assertEquals(listOf("a", "a.b", "l", "l[]", "l[].c"), paths)
        assertEquals(listOf("a"), SchemaInference.pathsOf(s, maxDepth = 1).map { it.toString() }.filter { it == "a" })
        assertNull(SchemaInference.typeAt(s, SchemaPath.of("nope")))
        val declared = SchemaInference.fromDeclared(SchemaHint.Static(SchemaNode(SchemaType.String)))
        assertEquals(SchemaOrigin.Declared, declared.origin)
        val registry = mapOf<String, (Map<String, Any?>) -> SchemaNode>("echo" to { p -> SchemaNode(if (p["n"] == 1L) SchemaType.Number else SchemaType.String) })
        assertEquals(SchemaType.Number, SchemaInference.fromDeclared(SchemaHint.Computed("echo"), registry, mapOf("n" to 1L))!!.type)
        assertNull(SchemaInference.fromDeclared(SchemaHint.Computed("gone"), registry, emptyMap()))
    }

    @Test fun `200 items with 50 fields infer quickly`() {
        val items = (1..200).map { i -> Item((1..50).associate { "field$it" to if (it % 3 == 0) "v$i" else i.toLong() }) }
        repeat(3) { SchemaInference.infer(items.asSequence()) }
        val t0 = System.nanoTime()
        val s = SchemaInference.infer(items.asSequence())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(50, s.fields.size)
        assertTrue("took ${ms}ms", ms < 30)
    }
}
