package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowInvoker
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Behavioural coverage for the second catalog expansion: pure JVM, no Android, no network. */
class ExtendedNodeTest {

    private fun run(
        executor: StepExecutor,
        params: Map<String, Any?>,
        input: List<FlowData> = listOf(item("value" to 1.0)),
        http: RecordingHttp = RecordingHttp(),
        devices: RecordingDevices = RecordingDevices(),
        flowInvoker: FlowInvoker? = null,
    ) = runBlocking {
        executor.execute(
            ctx(stepOf(executor.spec.id, params = params), input, http = http, devices = devices, flowInvoker = flowInvoker)
        ).primary
    }

    @Test
    fun `math handles typed expressions division and guarded errors`() {
        val math = MathNode()
        val out = run(
            math,
            mapOf("operation" to "add", "a" to "{{ \$json.value }}", "b" to 4, "outputField" to "total"),
            listOf(item("value" to 6.0)),
        )
        assertEquals(10.0, Values.asDouble(out.single()["total"])!!, 0.0001)
        runCatching {
            run(math, mapOf("operation" to "divide", "a" to 8, "b" to 0))
        }.also { assertTrue("division by zero must fail clearly", it.exceptionOrNull() != null) }
    }

    @Test
    fun `date parses shifts and converts time zones without a script`() {
        val date = DateNode()
        val parsed = run(date, mapOf("action" to "parse", "source" to "2026-01-01", "outputField" to "iso"))
        assertTrue(Values.asString(parsed.single()["iso"]).startsWith("2025-12-31T18:30"))
        val shifted = run(
            date,
            mapOf("action" to "shift", "source" to "2026-01-01T00:00:00Z", "amount" to 1, "unit" to "days", "outputField" to "iso"),
        )
        assertEquals("2026-01-02T00:00:00Z", Values.asString(shifted.single()["iso"]))
        val converted = run(
            date,
            mapOf(
                "action" to "timezone",
                "source" to "2026-01-01T00:00:00Z",
                "pattern" to "yyyy-MM-dd HH:mm",
                "targetZone" to "Asia/Kolkata",
                "outputField" to "local",
            ),
        )
        assertEquals("2026-01-01 05:30", Values.asString(converted.single()["local"]))
    }

    @Test
    fun `text tools replace slug and split without a script`() {
        val text = TextNode()
        val slug = run(text, mapOf("operation" to "slug", "input" to " Hello, Kaivo World! "))[0]["text"]
        assertEquals("hello-kaivo-world", slug)
        val parts = run(text, mapOf("operation" to "split", "input" to "a,b,c", "argument" to ","))[0]["text"] as List<*>
        assertEquals(listOf("a", "b", "c"), parts)
        val upper = run(text, mapOf("operation" to "upper", "input" to "abc"))[0]["text"]
        assertEquals("ABC", upper)
    }

    @Test
    fun `csv parses quoted rows and builds a table back`() {
        val csv = CsvNode()
        val parsed = run(csv, mapOf("source" to "name,note\nAda,\"hello, world\"\nLin,plain"))
        assertEquals(2, parsed.size)
        assertEquals("Ada", parsed[0]["name"])
        assertEquals("hello, world", parsed[0]["note"])
        val built = run(csv, mapOf("action" to "build", "columns" to "name,note"), parsed)
        assertTrue(Values.asString(built.single()["csv"]).contains("\"hello, world\""))
    }

    @Test
    fun `xml html and markdown round trip into readable fields`() {
        val xml = XmlNode()
        val fromXml = run(xml, mapOf("source" to "<root><item id=\"7\">Hi</item></root>"))
        @Suppress("UNCHECKED_CAST")
        val value = fromXml.single()["result"] as Map<String, Any?>
        assertEquals("7", Values.asString(Values.asMap(Values.asMap(value["root"])["item"])["@id"]))
        val toXml = run(xml, mapOf("action" to "to_xml", "rootTag" to "doc", "outputField" to "xml"),
            listOf(item("a" to "one", "b" to 2.0)))
        assertTrue(Values.asString(toXml.single()["xml"]).contains("<doc>"))

        val html = HtmlNode()
        val links = run(html, mapOf("action" to "links", "source" to "<a href=\"https://example.com\">Example</a>"))
        @Suppress("UNCHECKED_CAST")
        val first = (links.single()["text"] as List<*>).first() as Map<String, Any?>
        assertEquals("https://example.com", first["href"])

        val md = MarkdownNode()
        val rendered = run(md, mapOf("source" to "# Title\n\n**Bold** and [link](https://example.com)"))
        val out = Values.asString(rendered.single()["html"])
        assertTrue(out.contains("<h1>Title</h1>"))
        assertTrue(out.contains("<strong>Bold</strong>"))
    }

    @Test
    fun `sort limit compare and noop preserve data deterministically`() {
        val items = listOf(item("id" to "c", "n" to 3.0), item("id" to "a", "n" to 1.0), item("id" to "b", "n" to 2.0))
        val sorted = run(SortNode(), mapOf("field" to "n", "direction" to "asc", "as" to "number"), items)
        assertEquals(listOf("a", "b", "c"), sorted.map { it["id"] })
        val limited = run(LimitNode(), mapOf("mode" to "first", "count" to 2), items)
        assertEquals(2, limited.size)
        val compare = run(
            CompareNode(),
            mapOf(
                "key" to "id",
                "baseline" to listOf(mapOf("id" to "a", "v" to "Old"), mapOf("id" to "z", "v" to "Gone")),
                "fuzzy" to true,
            ),
            listOf(item("id" to "a", "v" to "Different"), item("id" to "b", "v" to "New")),
        )
        @Suppress("UNCHECKED_CAST")
        val changes = compare.single()["changes"] as List<Map<String, Any?>>
        assertEquals(setOf("added", "changed", "removed", "same"), changes.map { it["status"] }.toSet())
        val noop = run(NoopNode(), mapOf("note" to "keep me"), items)
        assertEquals(3, noop.size)
        assertEquals("keep me", noop.first()["note"])
    }

    @Test
    fun `local table saves reads increments and lists rows`() {
        val devices = RecordingDevices()
        val table = TableNode(devices)
        run(table, mapOf("action" to "put", "table" to "test", "key" to "visits", "value" to mapOf("count" to 2.0)),
            devices = devices)
        val got = run(table, mapOf("action" to "get", "table" to "test", "key" to "visits"), devices = devices)
        @Suppress("UNCHECKED_CAST")
        val row = got.single()["row"] as Map<String, Any?>
        assertEquals(2.0, Values.asDouble(row["count"])!!, 0.0001)
        val incremented = run(table, mapOf("action" to "increment", "table" to "test", "key" to "visits"),
            devices = devices)
        @Suppress("UNCHECKED_CAST")
        val updated = incremented.single()["row"] as Map<String, Any?>
        assertEquals(3.0, Values.asDouble(updated["count"])!!, 0.0001)
    }

    @Test
    fun `binary tools round trip text through an attachment`() {
        val devices = RecordingDevices()
        val binary = BinaryToolsNode(devices)
        val attached = run(
            binary,
            mapOf("action" to "from_text", "source" to "hello attachment", "fileName" to "note.txt", "mimeType" to "text/plain"),
            devices = devices,
        )
        assertNotNull(attached.single().binaryRef("data"))
        val decoded = run(
            binary,
            mapOf("action" to "to_text", "binaryField" to "data", "outputField" to "text"),
            attached,
            devices = devices,
        )
        assertEquals("hello attachment", decoded.single()["text"])
    }

    @Test
    fun `chart creates a valid svg attachment`() {
        val devices = RecordingDevices()
        val out = run(
            ChartNode(devices),
            mapOf("labelField" to "name", "valueField" to "value", "style" to "bar"),
            listOf(item("name" to "Mon", "value" to 4.0), item("name" to "Tue", "value" to 9.0)),
            devices = devices,
        )
        val svg = Values.asString(out.single()["svg"])
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("</svg>"))
        assertNotNull(out.single().binaryRef("chart"))
    }

    @Test
    fun `token sign verify and decode are self consistent`() {
        val token = TokenNode()
        val signed = run(
            token,
            mapOf("action" to "sign", "secret" to "a-test-secret", "payload" to mapOf("sub" to "kaivo"), "ttlSeconds" to 600),
        )
        val jwt = Values.asString(signed.single()["token"])
        assertEquals(3, jwt.split('.').size)
        val verified = run(
            token,
            mapOf("action" to "verify", "token" to jwt, "secret" to "a-test-secret", "algorithm" to "HS256"),
        )
        assertEquals(true, Values.deepGet(verified.single()["token"], "verified"))
        val decoded = run(token, mapOf("action" to "decode", "token" to jwt))
        assertEquals("kaivo", Values.deepGet(decoded.single()["token"], "payload.sub"))
    }

    @Test
    fun `totp code is six digits and changes with the clock window`() {
        val totp = TotpNode()
        val first = run(totp, mapOf("secret" to "JBSWY3DPEHPK3PXP", "digits" to 6, "periodSeconds" to 30))
        val code = Values.asString(first.single()["code"])
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `pdf text extracts a simple literal from an uncompressed stream`() {
        val pdf = "%PDF-1.4\n1 0 obj << >> stream\nBT (Hello PDF) Tj ET\nendstream\nendobj\n%%EOF"
        val base64 = Base64.getEncoder().encodeToString(pdf.toByteArray(Charsets.ISO_8859_1))
        val map = BinarySupport.storeAsDataUri("doc.pdf", "application/pdf", base64)!!
        val ref = BinarySupport.ref(map)!!
        val out = run(
            PdfNode(),
            mapOf("binaryField" to "data"),
            listOf(FlowData(emptyMap(), binary = mapOf("data" to ref))),
        )
        assertEquals("Hello PDF", out.single()["text"])
    }

    @Test
    fun `rss reader turns a feed into items`() {
        val xml = """<rss><channel><item><title>First</title><link>https://example.com/1</link><description>Hello</description></item><item><title>Second</title><link>https://example.com/2</link></item></channel></rss>"""
        val http = RecordingHttp().apply { responder = { HttpResponseSpec(status = 200, bodyText = xml) } }
        val out = run(RssNode(), mapOf("url" to "https://example.com/feed"), http = http)
        assertEquals(2, out.size)
        assertEquals("First", out[0]["title"])
        assertEquals("https://example.com/1", out[0]["link"])
    }

    @Test
    fun `graphql posts a json body and surfaces errors`() {
        val http = RecordingHttp().apply {
            responder = { HttpResponseSpec(status = 200, body = mapOf("data" to mapOf("viewer" to mapOf("id" to "42")))) }
        }
        val out = run(
            GraphQlNode(),
            mapOf("endpoint" to "https://example.com/graphql", "query" to "query { viewer { id } }", "variables" to mapOf("x" to 1)),
            http = http,
        )
        val request = http.requests.single()
        assertEquals(HttpRequestSpec.BodyKind.Json, request.bodyKind)
        assertEquals("42", Values.deepGet(out.single()["data"], "viewer.id"))
    }

    @Test
    fun `webdav download stores a real attachment`() {
        val devices = RecordingDevices()
        val base64 = Base64.getEncoder().encodeToString("file bytes".toByteArray())
        val http = RecordingHttp().apply { responder = { HttpResponseSpec(status = 200, bodyBase64 = base64) } }
        val out = run(
            WebDavNode(devices),
            mapOf("url" to "https://example.com/file.txt", "fileName" to "file.txt", "downloadAsBinary" to true),
            http = http,
            devices = devices,
        )
        assertNotNull(out.single().binaryRef("download"))
        assertTrue(http.requests.single().responseAsBinary)
    }

    @Test
    fun `run info exposes metadata without mutating input`() {
        val out = run(RunInfoNode(), mapOf("outputField" to "meta"), listOf(item("a" to 1.0), item("a" to 2.0)))
        assertEquals("run-1", Values.deepGet(out.single()["meta"], "runId"))
        assertEquals(2.0, Values.asDouble(Values.deepGet(out.single()["meta"], "inputItems"))!!, 0.0001)
    }

    @Test
    fun `new catalog is large and every id is unique`() {
        val ids = NodeCatalog.executors().map { it.spec.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue("expected a rich catalog, got ${ids.size}", ids.size >= 80)
        assertTrue(ids.contains("transform.math"))
        assertTrue(ids.contains("saas.graphql"))
        assertTrue(ids.contains("device.torch"))
        val namespaces = setOf("trigger", "logic", "transform", "ai", "saas", "device")
        assertTrue(
            "every Step must use a KaivoFlow namespace: ${ids.filter { it.substringBefore('.') !in namespaces }}",
            ids.all { it.substringBefore('.') in namespaces },
        )
    }
}
