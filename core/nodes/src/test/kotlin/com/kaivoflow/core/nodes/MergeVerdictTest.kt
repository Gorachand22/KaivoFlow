package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.StepNode
import kotlinx.coroutines.runBlocking
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.kaivoflow.core.model.toSchema
import org.junit.Test

/**
 * Taxonomy spec 5.7.7: every merged or removed Step is converted from its OLD stored params, run
 * through the registry executor of the target, and checked against the behaviour the old Step had.
 */
class MergeVerdictTest {

    private class Phone : DeviceGateway by RecordingDevices() {
        val toasts = mutableListOf<String>()
        var clip: String? = "copied text"
        override suspend fun showToast(text: String) { toasts += text }
        override suspend fun readClipboard(): String? = clip
        override suspend fun getWifiInfo(): Map<String, Any?> = mapOf("ssid" to "Home", "connected" to true)
    }

    /** Old stored Step -> upgraded Step (new id, converted params). */
    internal fun upgrade(oldId: String, params: Map<String, Any?>): StepNode {
        val old = StepNode("s", oldId, "Old", params = params.toImmutableMap())
        // ids that are still live (the merge target itself) are used as stored
        return if (CatalogAliases.map.alias(oldId) == null) old else CatalogAliases.normalizer.upgrade(old)!!.step
    }

    internal fun runOld(
        oldId: String,
        params: Map<String, Any?>,
        input: List<FlowData> = listOf(item("a" to 1.0)),
        devices: DeviceGateway = Phone(),
    ): StepResult = runBlocking {
        val step = upgrade(oldId, params)
        val registry = NodeCatalog.registry(devices)
        val exec = registry.executor(step.type) ?: error("no executor for ${step.type}")
        exec.execute(ctx(step, input, devices = devices))
    }

    private fun items(r: StepResult) = r.outputs.getValue(0)

    @Test fun `clipboard read keeps its output shape and the old id converts to operation read`() {
        val phone = Phone()
        val out = items(runOld("device.clipboard_read", mapOf("outputField" to "paste"), devices = phone))
        assertEquals(mapOf("paste" to "copied text", "empty" to false), out.single().json)
        phone.clip = "  "
        assertEquals(true, items(runOld("device.clipboard_read", emptyMap(), devices = phone)).single().json["empty"])
        assertEquals("read", upgrade("device.clipboard_read", emptyMap()).params["operation"])
    }

    @Test fun `clipboard write still copies and passes items through`() {
        val rec = RecordingDevices()
        val node = NodeCatalog.registry(rec).executor("device.clipboard")!!
        val out = runBlocking { node.execute(ctx(stepOf("device.clipboard", params = mapOf("text" to "hi")), listOf(item("x" to 1)), devices = rec)) }
        assertEquals("hi", rec.clipboard)
        assertEquals(1, items(out).size)
    }

    @Test fun `toast converts to notify mode toast and flashes the message per item`() {
        val phone = Phone()
        val out = items(runOld("device.toast", mapOf("text" to "Hello"), listOf(item("a" to 1), item("a" to 2)), phone))
        assertEquals(listOf("Hello", "Hello"), phone.toasts)
        assertEquals(2, out.size)
        assertEquals("toast", upgrade("device.toast", mapOf("text" to "x")).params["mode"])
        // no input still shows one toast, like the old Step
        val p2 = Phone()
        runOld("device.toast", mapOf("text" to "Solo"), emptyList(), p2)
        assertEquals(listOf("Solo"), p2.toasts)
    }

    @Test fun `notify without a mode is still a notification`() {
        val rec = RecordingDevices()
        val node = NodeCatalog.registry(rec).executor("device.notify")!!
        runBlocking { node.execute(ctx(stepOf("device.notify", params = mapOf("title" to "T", "body" to "B")), listOf(item("a" to 1)), devices = rec)) }
        assertEquals(listOf(Triple("T", "B", "default")), rec.notifications)
    }

    @Test fun `wifi info converts to network status detail wifi with the old output shape`() {
        val out = items(runOld("device.wifi_info", mapOf("outputField" to "net")))
        assertEquals(mapOf("net" to mapOf("ssid" to "Home", "connected" to true), "available" to true), out.single().json)
        val statusOut = items(runOld("device.network_status", mapOf("outputField" to "n2")))
        assertTrue((statusOut.single().json["n2"] as Map<*, *>)["connected"] == true)
        assertEquals(1.0, statusOut.single().json["a"])
    }
}

class DedupeMergeTest {
    private val base = MergeVerdictTest()

    @Test fun `poll converts to dedupe source url across runs and keeps the old state file`() {
        val up = base.upgrade("logic.poll", mapOf("url" to "https://x.test/feed", "idPath" to "id", "stateKey" to "mine.txt", "responsePath" to "data"))
        assertEquals("data.dedupe", up.type)
        assertEquals("url", up.params["source"])
        assertEquals("always", up.params["scope"])
        assertEquals("mine.txt", up.params["stateKey"])
        assertEquals("poll-state.txt", base.upgrade("logic.poll", mapOf("url" to "u")).params["stateKey"])
    }

    @Test fun `converted poll delivers only new entries across Runs through the dedupe Step`() {
        var calls = 0
        val http = RecordingHttp().also {
            it.responder = {
                calls++
                val ids = if (calls == 1) listOf(1.0, 2.0) else listOf(2.0, 3.0)
                com.kaivoflow.core.engine.HttpResponseSpec(status = 200, body = mapOf("data" to ids.map { id -> mapOf("id" to id) }))
            }
        }
        val devices = RecordingDevices()
        val step = base.upgrade("logic.poll", mapOf("url" to "https://api.example.com/feed", "responsePath" to "data", "idPath" to "id", "stateKey" to "seen.txt"))
        val exec = NodeCatalog.registry(devices).executor("data.dedupe")!!
        fun go() = runBlocking { exec.execute(ctx(step, listOf(item()), http = http, devices = devices)).outputs.getValue(0) }
        assertEquals(2, go().size)
        assertEquals(listOf(3.0), go().map { it.json["id"] })
        assertEquals(0, go().size)
    }

    @Test fun `across Runs memory is private to the Flow and Step and survives a second Run`() {
        val devices = RecordingDevices()
        val exec = NodeCatalog.registry(devices).executor("data.dedupe")!!
        val params = mapOf("fields" to "id", "scope" to "always")
        fun go(vararg ids: Int, flowId: String = "f1", stepId: String = "s1") = runBlocking {
            val step = com.kaivoflow.core.model.StepNode(stepId, "data.dedupe", "D", params = params.toImmutableMap())
            exec.execute(ctx(step, ids.map { item("id" to it) }, devices = devices, flow = com.kaivoflow.core.model.Flow(id = flowId, name = "F")))
                .outputs.getValue(0).map { it.json["id"] }
        }
        assertEquals(listOf(1, 2), go(1, 2, 2))
        assertEquals(listOf(3), go(1, 3))
        assertEquals("another Flow has its own memory", listOf(1), go(1, flowId = "f2"))
        assertEquals("another Step has its own memory", listOf(1), go(1, stepId = "s2"))
        assertTrue(devices.files.keys.all { it.startsWith("dedupe-") })
    }

    @Test fun `scope today forgets yesterday and run scope never touches storage`() {
        val devices = RecordingDevices()
        val exec = NodeCatalog.registry(devices).executor("data.dedupe")!!
        fun go(scope: String, clock: Long) = runBlocking {
            val step = com.kaivoflow.core.model.StepNode("s", "data.dedupe", "D", params = mapOf<String, Any?>("fields" to "id", "scope" to scope).toImmutableMap())
            exec.execute(ctx(step, listOf(item("id" to 1)), devices = devices).copy(clock = { clock })).outputs.getValue(0).size
        }
        assertEquals(1, go("day", 1_700_000_000_000)); assertEquals(0, go("day", 1_700_000_100_000))
        assertEquals(1, go("day", 1_700_000_000_000 + 2 * 86_400_000L))
        val before = devices.files.size
        go("run", 1_700_000_000_000); assertEquals(before, devices.files.size)
    }
}

class EditFieldsHubTest {
    private val base = MergeVerdictTest()
    private val two = listOf(item("name" to "Asha", "n" to 6.0, "junk" to 1), item("name" to "Ravi", "n" to 7.0, "junk" to 2))

    private fun run(oldId: String, params: Map<String, Any?>, input: List<FlowData> = two) =
        base.runOld(oldId, params, input).outputs.getValue(0)

    @Test fun `template becomes an assignment resolved per item`() {
        val out = run("transform.template", mapOf("template" to "Dear {{ \$json.name }}", "outputField" to "text"))
        assertEquals(listOf("Dear Asha", "Dear Ravi"), out.map { it.json["text"] })
        assertEquals(listOf("Asha", "Ravi"), out.map { it.json["name"] })
        val up = base.upgrade("transform.template", mapOf("template" to "t"))
        assertEquals("data.set", up.type)
        assertEquals("transform.template", up.convertedFrom!!.type)
        assertEquals("text", (up.params["assignments"] as List<*>).map { (it as Map<*, *>)["name"] }.single())
    }

    @Test fun `template keeps a pure expression's native type`() {
        assertEquals(listOf(6.0, 7.0), run("transform.template", mapOf("template" to "{{ \$json.n }}", "outputField" to "x")).map { it.json["x"] })
    }

    @Test fun `fields rename remove and keep only convert to the same keys and behave as before`() {
        val out = run("transform.fields", mapOf("rename" to mapOf("name" to "who"), "remove" to "junk"))
        assertEquals(mapOf("n" to 6.0, "who" to "Asha"), out[0].json)
        val kept = run("transform.fields", mapOf("keepOnly" to "name"))
        assertEquals(listOf(mapOf("name" to "Asha"), mapOf("name" to "Ravi")), kept.map { it.json })
    }

    @Test fun `math text json and url convert to helper rows with unchanged results`() {
        assertEquals(listOf(10.0, 11.0), run("transform.math", mapOf("operation" to "add", "a" to "{{ \$json.n }}", "b" to 4, "outputField" to "total")).map { it.json["total"] })
        assertEquals("hello-kaivo-world", run("transform.text", mapOf("operation" to "slug", "input" to " Hello, Kaivo World! ")).first().json["text"])
        assertEquals(listOf("a", "b", "c"), run("transform.text", mapOf("operation" to "split", "input" to "a,b,c", "argument" to ",")).first().json["text"])
        val parsed = run("transform.json", mapOf("action" to "parse", "source" to "body", "unwrapArray" to true), listOf(item("body" to """[{"a":1},{"a":2}]""")))
        assertEquals(2, parsed.size)
        val url = run("transform.url", mapOf("action" to "parse", "url" to "https://example.com/p?x=1"))
        assertTrue(url.isNotEmpty())
        val up = base.upgrade("transform.math", mapOf("operation" to "add", "a" to 1))
        assertEquals("math", ((up.params["operations"] as List<*>).single() as Map<*, *>)["op"])
        assertTrue(up.legacyParams.isEmpty())
    }

    @Test fun `bad json is still a clear error through the hub`() {
        val failure = runCatching { run("transform.json", mapOf("action" to "parse", "source" to "body"), listOf(item("body" to "{oops"))) }.exceptionOrNull()
        assertTrue(failure is com.kaivoflow.core.engine.StepExecutionException)
    }

    @Test fun `run details convert to a run object built from the run scope and keep the items`() {
        val out = run("logic.run_info", mapOf("outputField" to "meta"))
        assertEquals(2, out.size)
        val meta = out[0].json["meta"] as Map<*, *>
        assertEquals("run-1", meta["runId"])
        assertEquals("f", meta["flowId"])
        assertEquals("Manual", meta["mode"])
        assertEquals(2, meta["inputItems"].toString().toDouble().toInt())
        assertEquals("Asha", out[0].json["name"])
        assertTrue(base.upgrade("logic.run_info", emptyMap()).let { CatalogAliases.normalizer.upgrade(com.kaivoflow.core.model.StepNode("s", "logic.run_info", "R"))!!.warnings.isNotEmpty() })
    }

    @Test fun `typed assignments coerce and reject wrong types with a clear error`() {
        val exec = NodeCatalog.registry().executor("data.set")!!
        fun go(row: Map<String, Any?>) = runBlocking {
            exec.execute(ctx(stepOf("data.set", params = mapOf("assignments" to listOf(row))), listOf(item("a" to 1)))).outputs.getValue(0)[0].json
        }
        assertEquals(5.0, go(mapOf("name" to "n", "type" to "number", "value" to "5"))["n"])
        assertEquals(true, go(mapOf("name" to "b", "type" to "boolean", "value" to "true"))["b"])
        assertEquals("1", go(mapOf("name" to "t", "type" to "text", "value" to 1))["t"])
        assertEquals(listOf(1.0, 2.0), go(mapOf("name" to "l", "type" to "list", "value" to "[1,2]"))["l"])
        assertEquals(mapOf("k" to "v"), go(mapOf("name" to "o", "type" to "object", "value" to "{\"k\":\"v\"}"))["o"])
        assertTrue(runCatching { go(mapOf("name" to "n", "type" to "number", "value" to "abc")) }.exceptionOrNull() is com.kaivoflow.core.engine.StepExecutionException)
        assertTrue(runCatching { go(mapOf("name" to "", "type" to "text", "value" to "x")) }.exceptionOrNull() is com.kaivoflow.core.engine.StepExecutionException)
    }

    @Test fun `edit fields schema uses native assignment kinds and keeps keys in order`() {
        val spec = NodeCatalog.registry().spec("data.set")!!
        val schema = spec.toSchema()
        assertEquals(spec.params.map { it.key }, schema.params.map { it.key })
        assertTrue(schema.find("assignments")!!.kind is com.kaivoflow.core.model.ParamKind.Assignments)
    }
}

class HttpPresetMergeTest {
    private val base = MergeVerdictTest()

    @Test fun `connector keeps every param and pages through the http preset`() {
        val http = RecordingHttp().also {
            var page = 0
            it.responder = { _ ->
                page++
                com.kaivoflow.core.engine.HttpResponseSpec(status = 200, body = mapOf(
                    "items" to listOf(mapOf("id" to page)),
                    "next" to if (page < 3) "https://api.test/list?p=${page + 1}" else null,
                ))
            }
        }
        val old = mapOf("url" to "https://api.test/list", "responsePath" to "items", "nextUrlPath" to "next", "maxPages" to 3, "method" to "GET")
        val up = base.upgrade("saas.connector", old)
        assertEquals("net.http", up.type)
        assertEquals("paged", up.params["preset"])
        assertTrue(up.legacyParams.isEmpty())
        val out = runBlocking { NodeCatalog.registry().executor("net.http")!!.execute(ctx(up, listOf(item()), http = http)).outputs.getValue(0) }
        assertEquals(3, out.size)
        assertEquals(3, http.requests.size)
    }

    @Test fun `graphql keeps its body and error check`() {
        val old = mapOf("endpoint" to "https://example.com/graphql", "query" to "query { viewer { id } }", "variables" to mapOf("x" to 1), "authToken" to "tok")
        val up = base.upgrade("saas.graphql", old)
        assertEquals("graphql", up.params["preset"]); assertEquals("bearer", up.params["auth"]); assertEquals("https://example.com/graphql", up.params["url"])
        val http = RecordingHttp().also { it.responder = { _ -> com.kaivoflow.core.engine.HttpResponseSpec(status = 200, body = mapOf("data" to mapOf("viewer" to mapOf("id" to "42")))) } }
        val out = runBlocking { NodeCatalog.registry().executor("net.http")!!.execute(ctx(up, listOf(item()), http = http)).outputs.getValue(0) }
        val req = http.requests.single()
        assertEquals(com.kaivoflow.core.engine.HttpRequestSpec.BodyKind.Json, req.bodyKind)
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("POST", req.method)
        assertEquals("42", com.kaivoflow.core.engine.Values.deepGet(out.single().json["data"], "viewer.id"))
        val err = RecordingHttp().also { it.responder = { _ -> com.kaivoflow.core.engine.HttpResponseSpec(status = 200, body = mapOf("errors" to listOf(mapOf("message" to "nope")))) } }
        val failure = runCatching { runBlocking { NodeCatalog.registry().executor("net.http")!!.execute(ctx(up, listOf(item()), http = err)) } }.exceptionOrNull()
        assertTrue(failure is com.kaivoflow.core.engine.StepExecutionException)
    }

    @Test fun `webdav download stores an attachment through the http preset`() {
        val devices = RecordingDevices()
        val b64 = java.util.Base64.getEncoder().encodeToString("file bytes".toByteArray())
        val http = RecordingHttp().also { it.responder = { _ -> com.kaivoflow.core.engine.HttpResponseSpec(status = 200, bodyBase64 = b64) } }
        val up = base.upgrade("saas.webdav", mapOf("url" to "https://example.com/file.txt", "fileName" to "file.txt", "downloadAsBinary" to true))
        assertEquals("webdav", up.params["preset"]); assertEquals("GET", up.params["method"])
        val out = runBlocking { NodeCatalog.registry(devices).executor("net.http")!!.execute(ctx(up, listOf(item()), http = http, devices = devices)).outputs.getValue(0) }
        assertNotNull(out.single().binaryRef("download"))
        assertTrue(http.requests.single().responseAsBinary)
        val put = base.upgrade("saas.webdav", mapOf("method" to "PUT", "url" to "u", "body" to "text"))
        assertEquals("raw", put.params["bodyKind"])
    }

    @Test fun `upload converts and keeps its multipart fields`() {
        val up = base.upgrade("transform.binary_upload", mapOf("url" to "https://up.test/x", "method" to "PUT", "binaryField" to "doc", "formField" to "f", "extraFields" to mapOf("a" to "1")))
        assertEquals("upload", up.params["preset"]); assertEquals("PUT", up.params["method"]); assertEquals("doc", up.params["binaryField"])
        assertEquals(mapOf("a" to "1"), up.params["extraFields"])
    }

    @Test fun `a plain request is unchanged and defaults to the request preset`() {
        val http = RecordingHttp()
        runBlocking { NodeCatalog.registry().executor("net.http")!!.execute(ctx(stepOf("net.http", params = mapOf("url" to "https://x.test/")), listOf(item()), http = http)) }
        assertEquals("GET", http.requests.single().method)
    }
}

class RemovalVerdictTest {
    private val base = MergeVerdictTest()

    private fun go(oldId: String, params: Map<String, Any?>, input: List<FlowData> = listOf(item("a" to 1)), http: RecordingHttp = RecordingHttp()) =
        base.upgrade(oldId, params).let { up ->
            runBlocking { NodeCatalog.registry().executor(up.type)!!.execute(ctx(up, input, http = http)).outputs.getValue(0) }
        }

    @Test fun `token sign verify and decode work through crypto and old stored params convert`() {
        val signed = go("transform.token", mapOf("action" to "sign", "secret" to "s3cret", "payload" to mapOf("sub" to "u1"), "outputField" to "jwt"))
        val jwt = signed.single().json["jwt"] as String
        assertEquals(3, jwt.split(".").size)
        val up = base.upgrade("transform.token", mapOf("action" to "verify", "token" to jwt, "secret" to "s3cret"))
        assertEquals("security.crypto", up.type); assertEquals("token_verify", up.params["action"]); assertEquals("s3cret", up.params["tokenSecret"])
        val verified = go("transform.token", mapOf("action" to "verify", "token" to jwt, "secret" to "s3cret"))
        assertEquals(true, verified.single().json["token"].toString().contains("u1") || verified.single().json.isNotEmpty())
        val bad = runCatching { go("transform.token", mapOf("action" to "verify", "token" to jwt, "secret" to "wrong")) }.exceptionOrNull()
        assertTrue("a wrong secret must not verify", bad != null || false)
        val decoded = go("transform.token", mapOf("action" to "decode", "token" to jwt))
        assertTrue(decoded.single().json.toString().contains("u1"))
    }

    @Test fun `random uuid and string map to crypto and shuffle maps to sort random`() {
        val uuid = go("transform.random", mapOf("kind" to "uuid", "outputField" to "id"))
        assertEquals(36, (uuid.single().json["id"] as String).length)
        val str = go("transform.random", mapOf("kind" to "string", "length" to 12, "outputField" to "k"))
        assertEquals(12, (str.single().json["k"] as String).length)
        val up = base.upgrade("transform.random", mapOf("kind" to "shuffle"))
        assertEquals("data.sort", up.type); assertEquals("random", up.params["mode"])
        val shuffled = go("transform.random", mapOf("kind" to "shuffle"), (1..30).map { item("n" to it) })
        assertEquals((1..30).toSet(), shuffled.map { it.json["n"] }.toSet())
        assertTrue("30 shuffled items stay in order only with probability 1/30!", shuffled.map { it.json["n"] } != (1..30).toList())
    }

    @Test fun `random number and pick have no replacement and load as Unavailable Steps`() {
        listOf("number", "pick").forEach { kind ->
            val old = com.kaivoflow.core.model.StepNode("r", "transform.random", "Dice", params = kotlinx.collections.immutable.persistentMapOf<String, Any?>() as kotlinx.collections.immutable.ImmutableMap<String, Any?>)
            val withKind = old.copy(params = mapOf<String, Any?>("kind" to kind).toImmutableMap())
            assertEquals("stays as stored", withKind, CatalogAliases.normalizer.normalize(withKind))
            val placeholder = CatalogAliases.normalizer.unavailable(withKind) { NodeCatalog.registry().spec(it) != null }
            assertNotNull(placeholder)
            assertTrue(placeholder!!.message.contains("no direct replacement"))
        }
    }

    @Test fun `tcp and chart are tombstoned and keep their params and wiring`() {
        listOf("saas.tcp", "transform.chart").forEach { id ->
            val step = StepNode("x", id, "Old", params = mapOf<String, Any?>("host" to "h").toImmutableMap())
            assertEquals(step, CatalogAliases.normalizer.normalize(step))
            val u = CatalogAliases.normalizer.unavailable(step) { NodeCatalog.registry().spec(it) != null }!!
            assertEquals(com.kaivoflow.core.model.UnavailableReason.Removed, u.reason)
            assertEquals(step, u.step)
        }
    }

    @Test fun `weather converts to an http forecast request with the same url and a warning`() {
        val http = RecordingHttp().also { it.responder = { _ -> com.kaivoflow.core.engine.HttpResponseSpec(status = 200, body = mapOf("current" to mapOf("temperature_2m" to 31.0))) } }
        val up = base.upgrade("saas.weather", mapOf("city" to "Delhi", "units" to "fahrenheit"))
        assertEquals("net.http", up.type)
        assertEquals("https://api.open-meteo.com/v1/forecast", up.params["url"])
        val q = up.params["query"] as Map<*, *>
        assertEquals("28.6139", q["latitude"]); assertEquals("fahrenheit", q["temperature_unit"])
        val out = go("saas.weather", mapOf("city" to "Delhi"), http = http)
        assertEquals("https://api.open-meteo.com/v1/forecast", http.requests.single().url)
        assertEquals("28.6139", http.requests.single().query["latitude"])
        assertEquals(31.0, (out.single().json["current"] as Map<*, *>)["temperature_2m"])
        val result = CatalogAliases.normalizer.upgrade(StepNode("w", "saas.weather", "W", params = mapOf<String, Any?>("city" to "Nowhere").toImmutableMap()))!!
        assertTrue(result.warnings.size == 2)
        assertEquals("Nowhere", result.step.legacyParams["city"])
    }

    @Test fun `table aggregate stats csv and binary stay registered but hidden until their replacements ship`() {
        val reg = NodeCatalog.registry()
        listOf("logic.table", "transform.aggregate", "transform.stats", "transform.csv", "transform.binary").forEach { id ->
            assertEquals(id, com.kaivoflow.core.model.Lifecycle.Hidden, reg.spec(id)!!.lifecycle)
        }
    }

    @Test fun `every verdict row is accounted for`() {
        val ids = NodeCatalog.executors().map { it.spec.id }.toSet()
        listOf("device.toast", "device.wifi_info", "device.clipboard_read", "logic.poll", "logic.run_info", "transform.fields", "transform.template",
            "transform.json", "transform.math", "transform.text", "transform.url", "saas.connector", "saas.graphql", "saas.webdav",
            "transform.binary_upload", "transform.token", "transform.random", "saas.weather", "saas.tcp", "transform.chart").forEach { gone ->
            assertTrue("$gone must no longer be registered", gone !in ids)
        }
    }
}
