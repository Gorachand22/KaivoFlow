package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowInvoker
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for composition, connector, polling and starter-template plumbing. */
class ExtendedCompositionTest {

    private fun run(
        executor: StepExecutor,
        params: Map<String, Any?>,
        input: List<FlowData> = listOf(item("value" to 1.0)),
        http: RecordingHttp = RecordingHttp(),
        devices: RecordingDevices = RecordingDevices(),
        flowInvoker: FlowInvoker? = null,
    ) = runBlocking {
        executor.execute(
            ctx(
                stepOf(executor.spec.id, params = params),
                input,
                http = http,
                devices = devices,
                flowInvoker = flowInvoker,
            )
        ).primary
    }

    @Test
    fun `service connector follows a response path and a next-page URL`() {
        val http = RecordingHttp()
        var page = 0
        http.responder = { _: HttpRequestSpec ->
            page++
            if (page == 1) {
                HttpResponseSpec(
                    status = 200,
                    body = mapOf(
                        "data" to listOf(mapOf("id" to 1.0), mapOf("id" to 2.0)),
                        "links" to mapOf("next" to "https://api.example.com/page2"),
                    ),
                )
            } else {
                HttpResponseSpec(
                    status = 200,
                    body = mapOf(
                        "data" to listOf(mapOf("id" to 3.0)),
                        "links" to emptyMap<String, Any?>(),
                    ),
                )
            }
        }
        val out = run(
            ServiceConnectorNode(),
            mapOf(
                "url" to "https://api.example.com/items",
                "responsePath" to "data",
                "nextUrlPath" to "links.next",
                "maxPages" to 2,
            ),
            http = http,
        )
        assertEquals(3, out.size)
        assertEquals(1.0, Values.asDouble(out[0]["id"])!!, 0.0001)
        assertEquals(3.0, Values.asDouble(out[2]["id"])!!, 0.0001)
        assertEquals(2, http.requests.size)
        assertEquals("https://api.example.com/page2", http.requests[1].url)
    }

    @Test
    fun `poll remembers seen ids between runs`() {
        val http = RecordingHttp()
        var calls = 0
        http.responder = { _: HttpRequestSpec ->
            calls++
            val ids = if (calls == 1) listOf(1.0, 2.0) else listOf(2.0, 3.0)
            HttpResponseSpec(status = 200, body = mapOf("data" to ids.map { mapOf("id" to it) }))
        }
        val devices = RecordingDevices()
        val params = mapOf(
            "url" to "https://api.example.com/feed",
            "responsePath" to "data",
            "idPath" to "id",
            "stateKey" to "seen.txt",
        )
        val node = PollChangesNode(devices)
        val first = run(node, params, http = http, devices = devices)
        assertEquals(2, first.size)
        val second = run(node, params, http = http, devices = devices)
        assertEquals(1, second.size)
        assertEquals(3.0, Values.asDouble(second.single()["id"])!!, 0.0001)
        val third = run(node, params, http = http, devices = devices)
        assertEquals(0, third.size)
        assertTrue((devices.files["seen.txt"] ?: "").lines().filter { it.isNotBlank() }.size >= 3)
    }

    @Test
    fun `sub flow invokes a child flow and returns its items`() {
        val invoker = object : FlowInvoker {
            override suspend fun invoke(flowId: String, items: List<FlowData>, depth: Int): List<FlowData> {
                assertEquals("child", flowId)
                assertEquals(1, depth)
                return items.map { FlowData(it.json + ("seen" to true)) }
            }
        }
        val out = run(
            SubFlowNode(),
            mapOf("flowId" to "child"),
            listOf(item("n" to 2.0)),
            flowInvoker = invoker,
        )
        assertEquals(2.0, Values.asDouble(out.single()["n"])!!, 0.0001)
        assertEquals(true, out.single()["seen"])
    }

    @Test
    fun `catalog registers the new composition steps exactly once`() {
        val ids = NodeCatalog.executors().map { it.spec.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(87, ids.size)
        assertTrue(ids.contains("logic.subflow"))
        assertTrue(ids.contains("logic.poll"))
        assertTrue(ids.contains("saas.connector"))
    }
}
