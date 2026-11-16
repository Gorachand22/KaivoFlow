package com.kaivoflow.core.data

import com.kaivoflow.core.nodes.CatalogAliases
import com.kaivoflow.core.nodes.NodeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures of the OLD stored Flow JSON (ids from before the 2C rename). Loading must normalise ids,
 * keep params and wiring, and the next save must write only new ids (taxonomy spec 6.5).
 */
class StepFixturesTest {
    private val registry = NodeCatalog.registry()

    /** Old id to the id a loaded Flow must carry now. Filled per verdict change. */
    private val fixtures: List<Triple<String, String, String>> = listOf(
        Triple("trigger.manual", "spark.manual", """{}"""),
        Triple("trigger.schedule", "spark.schedule", """{"frequency":"daily","time":"07:30"}"""),
        Triple("trigger.webhook", "spark.webhook", """{"path":"hook-1","method":"POST"}"""),
        Triple("trigger.battery", "spark.battery", """{}"""),
        Triple("logic.if", "flow.if", """{"combinator":"and"}"""),
        Triple("logic.filter", "data.filter", """{"combinator":"and"}"""),
        Triple("logic.dedupe", "data.dedupe", """{"fields":"id"}"""),
        Triple("logic.wait", "flow.wait", """{"seconds":2}"""),
        Triple("transform.set", "data.set", """{"fields":{"a":"1"}}"""),
        Triple("transform.date", "data.date", """{"operation":"now"}"""),
        Triple("transform.code", "code.javascript", """{"code":"return items"}"""),
        Triple("transform.crypto", "security.crypto", """{"operation":"hash"}"""),
        Triple("transform.pdf", "files.pdf", """{}"""),
        Triple("saas.http", "net.http", """{"url":"https://example.com","method":"GET"}"""),
        Triple("saas.rss", "net.rss", """{"url":"https://example.com/feed"}"""),
        Triple("saas.telegram", "app.telegram", """{"chatId":"@x","text":"hi"}"""),
        Triple("saas.sheets", "app.google_sheets", """{"action":"read"}"""),
        Triple("device.notify", "device.notify", """{"title":"t","body":"b"}"""),
        // merged or converted at load (taxonomy spec 5.7.7)
        Triple("device.toast", "device.notify", """{"text":"hi"}"""),
        Triple("device.clipboard_read", "device.clipboard", """{"outputField":"c"}"""),
        Triple("device.wifi_info", "device.network_status", """{"outputField":"w"}"""),
        Triple("logic.poll", "data.dedupe", """{"url":"https://x.test/feed","idPath":"id","stateKey":"s.txt"}"""),
        Triple("transform.template", "data.set", """{"template":"Hi {{ ${'$'}json.name }}","outputField":"msg"}"""),
        Triple("transform.math", "data.set", """{"operation":"add","a":1,"b":2,"outputField":"t"}"""),
        Triple("transform.fields", "data.set", """{"remove":"junk"}"""),
        Triple("saas.connector", "net.http", """{"url":"https://x.test","maxPages":2}"""),
        Triple("saas.graphql", "net.http", """{"endpoint":"https://x.test/g","query":"query { a }"}"""),
        Triple("saas.weather", "net.http", """{"city":"Delhi"}"""),
        Triple("transform.token", "security.crypto", """{"action":"decode","token":"a.b.c"}"""),
    )

    private fun oldJson(): String {
        val steps = fixtures.mapIndexed { i, (old, _, params) ->
            """{"id":"s$i","type":"$old","name":"Step $i","x":1.0,"y":2.0,"params":$params}"""
        }.joinToString(",")
        val streams = (1 until fixtures.size).joinToString(",") {
            """{"id":"e$it","from":"s${it - 1}","to":"s$it","fromPort":0,"toPort":0,"label":""}"""
        }
        return """{"id":"f1","name":"Old","createdAt":1,"updatedAt":2,"steps":[$steps],"streams":[$streams]}"""
    }

    @Test fun `at least ten distinct old Steps are covered`() = assertTrue(fixtures.map { it.first }.toSet().size >= 10)

    @Test fun `an old Flow loads with new ids, params and wiring intact`() {
        val loaded = JsonCodec.decodeFlow(oldJson())
        assertNotNull(loaded)
        loaded!!
        assertEquals(fixtures.map { it.second }, loaded.steps.map { it.type })
        assertEquals("07:30", loaded.steps[1].params["time"])
        assertEquals("hook-1", loaded.steps[2].params["path"])
        val msg = loaded.steps[fixtures.indexOfFirst { it.first == "transform.template" }]
        assertEquals("transform.template", msg.convertedFrom!!.type)
        assertEquals("msg", ((msg.params["assignments"] as List<*>).single() as Map<*, *>)["name"])
        val poll = loaded.steps[fixtures.indexOfFirst { it.first == "logic.poll" }]
        assertEquals("s.txt", poll.params["stateKey"])
        assertEquals("url", poll.params["source"])
        assertEquals(fixtures.size - 1, loaded.streams.size)
        loaded.steps.forEach { assertNotNull("${it.type} must be runnable", registry.spec(it.type)) }
    }

    @Test fun `re-saving writes only new ids and a second load is stable`() {
        val loaded = JsonCodec.decodeFlow(oldJson())!!
        val saved = JsonCodec.encodeFlow(loaded)
        // The old id may survive only as conversion provenance, never as a Step type.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(saved) as kotlinx.serialization.json.JsonObject
        val types = (root["steps"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonObject)["type"]!!.toString().trim('"') }
        assertEquals(fixtures.map { it.second }, types)
        assertEquals(saved, JsonCodec.encodeFlow(JsonCodec.decodeFlow(saved)!!))
    }

    @Test fun `removed Steps without a converter survive load and save and show as Unavailable`() {
        val one = """{"id":"f","name":"n","steps":[{"id":"a","type":"saas.tcp","name":"Socket","params":{"host":"h"}},{"id":"b","type":"transform.random","name":"Dice","params":{"kind":"number"}}],"streams":[]}"""
        val loaded = JsonCodec.decodeFlow(one)!!
        assertEquals(listOf("saas.tcp", "transform.random"), loaded.steps.map { it.type })
        assertEquals("h", loaded.steps[0].params["host"])
        val unavailable = CatalogAliases.normalizer.unavailable(loaded) { registry.spec(it) != null }
        assertEquals(2, unavailable.size)
        assertEquals(JsonCodec.encodeFlow(loaded), JsonCodec.encodeFlow(JsonCodec.decodeFlow(JsonCodec.encodeFlow(loaded))!!))
    }

    @Test fun `every renamed alias loads through the codec`() {
        CatalogAliases.renames.forEach { (old, new) ->
            val one = """{"id":"f","name":"n","steps":[{"id":"a","type":"$old","name":"A","params":{"k":"v"}}],"streams":[]}"""
            val step = JsonCodec.decodeFlow(one)!!.steps.single()
            assertEquals(new, step.type)
            assertEquals("v", step.params["k"])
        }
    }
}



