package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLogicTest {
    private val items = listOf(
        Item(mapOf("id" to 1, "name" to "Ann", "tags" to listOf("a", "b"), "addr" to mapOf("city" to "Oslo", "zip" to "0150"))),
        Item(mapOf("id" to 2, "name" to null, "extra" to true, "addr" to mapOf("city" to "Rome"))),
        Item(mapOf("id" to 3.0, "name" to "", "tags" to emptyList<String>())),
    )

    // ---- schema ----

    @Test fun schemaRowsDefaultDepthAndOptional() {
        val root = SchemaInference.infer(items.asSequence())
        val rows = SchemaTreeFlattener.flatten(root)
        val labels = rows.map { it.label }
        assertTrue(labels.containsAll(listOf("id", "name", "tags", "addr", "extra")))
        // default opens the top level: its children are listed, deeper levels are not
        assertTrue("city" in labels)
        assertFalse("city" in SchemaTreeFlattener.flatten(root, TreeExpansion.Collapsed).map { it.label })
        val extra = rows.first { it.label == "extra" }
        assertTrue(extra.optional)
        assertEquals("seen in 1 of 3", extra.seenLabel)
        assertEquals(SchemaType.Bool, extra.type)
        assertNull(rows.first { it.label == "id" }.seenLabel)
    }

    @Test fun schemaExpandAndElementRow() {
        val root = SchemaInference.infer(items.asSequence())
        val all = SchemaTreeFlattener.flatten(root, TreeExpansion.All)
        assertTrue(all.any { it.label == "city" && it.depth == 1 && it.path == SchemaPath.of("addr", "city") })
        val el = all.first { it.label == "[0]" }
        assertEquals(SchemaPath.of("tags", SchemaPath.ELEMENT), el.path)
        assertEquals("{{ \$json.tags[0] }}", ReferenceBuilder.build(PanelSources.json(PanelOrigin("s", "S"), el.path)))
        val one = TreeExpansion.Collapsed.toggle(idOf(listOf("addr")), 0)
        val rows = SchemaTreeFlattener.flatten(root, one)
        assertTrue(rows.first { it.label == "addr" }.expanded)
        assertTrue(rows.any { it.label == "zip" })
        assertFalse(SchemaTreeFlattener.flatten(root, one.toggle(idOf(listOf("addr")), 0)).any { it.label == "zip" })
    }

    @Test fun schemaSearchKeepsAncestorsAndOpensThem() {
        val root = SchemaInference.infer(items.asSequence())
        val rows = SchemaTreeFlattener.flatten(root, query = "zip")
        assertEquals(listOf("addr", "zip"), rows.map { it.label })
        assertTrue(rows[0].expanded)
        assertTrue(rows[1].matches)
        assertFalse(rows[0].matches)
        assertTrue(SchemaTreeFlattener.flatten(root, query = "nomatch").isEmpty())
    }

    @Test fun schemaRowCap() {
        val root = SchemaInference.infer(items.asSequence())
        assertEquals(2, SchemaTreeFlattener.flatten(root, TreeExpansion.All, maxRows = 2).size)
    }

    @Test fun expansionToggleRoundTrip() {
        var e = TreeExpansion()
        assertTrue(e.isOpen("a", 0))
        assertFalse(e.isOpen("a", 1))
        e = e.toggle("a", 0)
        assertFalse(e.isOpen("a", 0))
        e = e.toggle("a", 0)
        assertTrue(e.isOpen("a", 0))
        assertTrue(TreeExpansion.All.isOpen("x", 99))
        assertFalse(TreeExpansion.Collapsed.isOpen("x", 0))
    }

    // ---- table ----

    @Test fun tableColumnsUnionFirstSeen() {
        val m = TableBuilder.build(items, listOf(10, 11, 12))
        assertEquals(listOf("id", "name", "tags", "addr", "extra"), m.columns.map { it.key })
        assertEquals(SchemaType.Number, m.columns[0].type)
        assertEquals(SchemaType.String, m.columns[1].type)
        assertEquals(listOf(10, 11, 12), m.rows.map { it.itemIndex })
        assertEquals(0, m.hiddenColumns)
    }

    @Test fun tableCells() {
        val m = TableBuilder.build(items)
        val r1 = m.rows[1].cells
        assertEquals(CellKind.Null, r1[1].kind)         // explicit null
        assertEquals("null", r1[1].text)
        assertEquals(CellKind.Text, r1[2].kind)         // missing key: blank
        assertEquals("", r1[2].text)
        assertEquals("{1}", r1[3].text)
        assertTrue(r1[3].isSummary)
        assertEquals("true", r1[4].text)
        assertEquals(CellKind.EmptyString, m.rows[2].cells[1].kind)
        assertEquals("3", m.rows[2].cells[0].text)      // 3.0 shown as 3
        assertTrue(m.rows[2].cells[0].rightAligned)
        assertEquals("[2]", m.rows[0].cells[2].text)
        assertEquals("[\"a\",\"b\"]", m.rows[0].cells[2].full)
    }

    @Test fun cellTruncationAndNewlines() {
        val c = TableBuilder.cell("x".repeat(100) + "\nmore", maxChars = 10)
        assertTrue(c.truncated)
        assertEquals(10, c.text.length)
        assertTrue(c.text.endsWith("…"))
        assertTrue('\n' in c.full)
        assertFalse('\n' in TableBuilder.cell("a\nb").text)
    }

    @Test fun columnCapAndWidth() {
        val wide = Item((1..70).associate { "k$it" to it })
        val m = TableBuilder.build(listOf(wide), maxColumns = 60)
        assertEquals(60, m.columns.size)
        assertEquals(10, m.hiddenColumns)
        assertEquals(96, TableBuilder.columnWidthDp("a", emptyList()))
        assertEquals(240, TableBuilder.columnWidthDp("a", listOf(TableBuilder.cell("y".repeat(200), 200))))
    }

    @Test fun mixedAndAllNullColumnTypes() {
        val m = TableBuilder.build(listOf(Item(mapOf("a" to 1, "b" to null)), Item(mapOf("a" to "x", "b" to null))))
        assertEquals(SchemaType.Mixed, m.columns[0].type)
        assertEquals(SchemaType.Null, m.columns[1].type)
    }

    @Test fun selectionAndHighlight() {
        var s = RowSelection()
        s = s.toggle(3).toggle(5)
        assertEquals(2, s.count)
        assertTrue(s.isSelected(3))
        s = s.toggle(3)
        assertFalse(s.isSelected(3))
        assertEquals(0, s.clear().count)
        assertEquals(listOf(0..1, 4..5), TableBuilder.highlightRanges("abxxAB", "ab"))
        assertTrue(TableBuilder.highlightRanges("abc", " ").isEmpty())
    }

    // ---- json ----

    @Test fun jsonDefaultsAndExpandAll() {
        val r = JsonTreeFlattener.flatten(items, listOf(0, 1, 2))
        assertEquals(JsonKind.ItemHeader, r.rows[0].kind)
        assertEquals("Item 1", r.rows[0].label)
        assertTrue(r.rows.none { it.label == "city" })
        val all = JsonTreeFlattener.flatten(items, expansion = TreeExpansion.All)
        val city = all.rows.first { it.label == "city" }
        assertEquals(SchemaPath.of("addr", "city"), city.path)
        val tag = all.rows.first { it.label == "[1]" }
        assertEquals(SchemaPath.of("tags", "[1]"), tag.path)
        assertEquals("{{ \$json.tags[1] }}", ReferenceBuilder.build(PanelSources.node(PanelOrigin("s", "S"), tag)))
        val collapsed = JsonTreeFlattener.flatten(items, expansion = TreeExpansion.Collapsed)
        assertEquals(3, collapsed.rows.size)
        assertTrue(collapsed.rows.all { it.kind == JsonKind.ItemHeader })
    }

    @Test fun jsonSearchAutoExpandsAndCounts() {
        val r = JsonTreeFlattener.flatten(items, query = "oslo")
        assertEquals(1, r.matchCount)
        assertEquals(1, r.matchPositions.size)
        assertEquals("city", r.rows[r.matchPositions[0]].label)
        assertTrue(r.rows.any { it.label == "addr" && it.expanded })
        assertEquals(0, JsonTreeFlattener.flatten(items, query = "zzz").matchCount)
        assertEquals(2, JsonTreeFlattener.flatten(items, query = "addr").rows.count { it.matches })
    }

    @Test fun jsonTextIsValidAndEscaped() {
        val v = mapOf("a" to "q\"\\\n\u0001", "b" to listOf(1, 2.5, null, true), "c" to emptyMap<String, Any?>(), "d" to Double.NaN)
        assertEquals("{\"a\":\"q\\\"\\\\\\n\\u0001\",\"b\":[1,2.5,null,true],\"c\":{},\"d\":null}", JsonText.compact(v))
        val p = JsonText.pretty(mapOf("x" to listOf(1), "y" to emptyList<Int>()))
        assertEquals("{\n  \"x\": [\n    1\n  ],\n  \"y\": []\n}", p)
    }

    // ---- paging and format ----

    @Test fun paging() {
        val i = Paging.info(25, 25, 480)
        assertEquals("2 / 20", i.pageLabel)
        assertEquals("26-50", i.rangeLabel)
        assertTrue(i.hasPrev && i.hasNext)
        assertEquals(0, i.prevOffset)
        assertEquals(50, i.nextOffset)
        val last = Paging.info(9999, 25, 480)
        assertEquals(20, last.page)
        assertFalse(last.hasNext)
        assertEquals(480, last.to)
        val empty = Paging.info(0, 25, 0)
        assertEquals("1 / 1", empty.pageLabel)
        assertEquals("0", empty.rangeLabel)
        assertEquals(50, Paging.offsetForPage(3, 25, 480))
        assertEquals(475 - 475 % 10, Paging.offsetAfterSizeChange(475, 10, 480))
        assertEquals(0, Paging.offsetAfterSizeChange(40, 100, 60))
        assertEquals("12 items", Paging.countLabel(12, 12))
        assertEquals("1 item", Paging.countLabel(1, 1))
        assertEquals("200 of 3400 items", Paging.countLabel(200, 3400))
        assertEquals("4 of 25 rows", Paging.matchLabel(4, 25))
    }

    @Test fun format() {
        assertEquals("812 B", PanelFormat.bytes(812))
        assertEquals("2 KB", PanelFormat.bytes(2048))
        assertEquals("1.5 MB", PanelFormat.bytes(1536L * 1024))
        assertEquals("just now", PanelFormat.age(100_000, 99_000))
        assertEquals("4 min ago", PanelFormat.age(1_000_000, 1_000_000 - 4 * 60_000L))
        assertEquals("Pinned, 3 items, 2 KB, 4 min ago", PanelFormat.pinnedLine(3, 2048, 1_000_000, 1_000_000 - 4 * 60_000L))
        assertEquals("Run 2 of 5", PanelFormat.runLabel(2, 5))
        assertEquals("abcdef12…cdef", PanelFormat.shortChecksum("abcdef1234567890abcdef"))
    }

    @Test fun sources() {
        val o = PanelOrigin("s1", "HTTP", isImmediateParent = false)
        val col = PanelSources.column(o, TableColumn("first name", SchemaType.String), 2)
        assertEquals("{{ \$step(\"HTTP\")[2][\"first name\"] }}", ReferenceBuilder.build(col))
        val b = PanelSources.binaryFileName(PanelOrigin("s", "S"), "photo")
        assertEquals("{{ \$binary.photo.fileName }}", ReferenceBuilder.build(b))
    }
}
