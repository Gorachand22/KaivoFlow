package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.ai.chains.AiChatNode
import com.kaivoflow.core.nodes.ai.chains.AiExtractNode
import com.kaivoflow.core.nodes.app.messaging.TelegramNode
import com.kaivoflow.core.nodes.app.productivity.GoogleSheetsNode
import com.kaivoflow.core.nodes.code.script.CodeNode
import com.kaivoflow.core.nodes.data.items.AggregateNode
import com.kaivoflow.core.nodes.data.items.DedupeNode
import com.kaivoflow.core.nodes.data.items.FieldsNode
import com.kaivoflow.core.nodes.data.items.FilterNode
import com.kaivoflow.core.nodes.data.items.JsonNode
import com.kaivoflow.core.nodes.data.items.SetFieldsNode
import com.kaivoflow.core.nodes.data.time.DateNode
import com.kaivoflow.core.nodes.device.files_media.FileNode
import com.kaivoflow.core.nodes.device.notifications.NotificationSendNode
import com.kaivoflow.core.nodes.device.telephony.SmsSendNode
import com.kaivoflow.core.nodes.flow.branching.IfNode
import com.kaivoflow.core.nodes.flow.branching.MergeNode
import com.kaivoflow.core.nodes.flow.branching.SwitchNode
import com.kaivoflow.core.nodes.flow.looping.LoopNode
import com.kaivoflow.core.nodes.net.web.HttpNode
import com.kaivoflow.core.nodes.spark.start.ManualTriggerNode
import com.kaivoflow.core.nodes.spark.start.ScheduleTriggerNode
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeExecutorTest {

    // ---------------------------------------------------------------- HTTP
    @Test
    fun `http node builds a GET with query and unwraps a json array into items`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(status = 200, body = listOf(mapOf("id" to 1), mapOf("id" to 2)), durationMs = 12)
        }
        val node = HttpNode()
        val result = node.execute(
            ctx(
                stepOf(
                    "net.http",
                    params = mapOf(
                        "method" to "GET",
                        "url" to "https://api.test/items",
                        "query" to mapOf("limit" to "2"),
                    ),
                ),
                listOf(item("seed" to 1)),
                http = http,
            )
        )
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests[0].method)
        assertEquals("2", http.requests[0].query["limit"])
        assertEquals(2, result.port(0).size)
        assertEquals(1, result.port(0)[0].json["id"])
        assertEquals(200.0, (result.port(0)[0].json["statusCode"] as Number).toDouble(), 0.001)
    }

    @Test
    fun `http node sends json body and bearer auth`() = runBlocking {
        val http = RecordingHttp()
        val node = HttpNode()
        node.execute(
            ctx(
                stepOf(
                    "net.http",
                    params = mapOf(
                        "method" to "POST",
                        "url" to "https://api.test/items",
                        "auth" to "bearer",
                        "authToken" to "abc123",
                        "bodyKind" to "json",
                        "body" to mapOf("name" to "x"),
                    ),
                ),
                listOf(item()),
                http = http,
            )
        )
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("Bearer abc123", request.headers["Authorization"])
        assertEquals(mapOf("name" to "x"), request.body)
    }

    @Test
    fun `http node fails with a readable message on 4xx`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 401, bodyText = "{\"error\":\"bad key\"}") }
        val error = runCatching {
            HttpNode().execute(
                ctx(stepOf("net.http", params = mapOf("method" to "GET", "url" to "https://api.test/x")), listOf(item()), http = http)
            )
        }.exceptionOrNull()
        assertTrue(error is StepExecutionException)
        assertTrue(error!!.message!!.contains("401"))
    }

    @Test
    fun `http node can ignore error statuses`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 404, body = mapOf("missing" to true)) }
        val result = HttpNode().execute(
            ctx(
                stepOf("net.http", params = mapOf("method" to "GET", "url" to "https://api.test/x", "ignoreHttpErrors" to true)),
                listOf(item()),
                http = http,
            )
        )
        assertEquals(404.0, (result.port(0).first().json["statusCode"] as Number).toDouble(), 0.001)
    }

    @Test
    fun `http node rejects a url without a scheme`() = runBlocking {
        val error = runCatching {
            HttpNode().execute(ctx(stepOf("net.http", params = mapOf("url" to "api.test/x")), listOf(item())))
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("http"))
    }

    // ---------------------------------------------------------------- logic
    @Test
    fun `if node splits items into true and false ports`() = runBlocking {
        val result = IfNode().execute(
            ctx(
                stepOf(
                    "flow.if",
                    params = mapOf(
                        "combinator" to "all",
                        "conditions" to listOf(mapOf("left" to "{{ \$json.amount }}", "operator" to "Greater", "right" to 500)),
                    ),
                ),
                listOf(item("amount" to 100), item("amount" to 900)),
            )
        )
        assertEquals(1, result.port(0).size)
        assertEquals(1, result.port(1).size)
        assertEquals(900, result.port(0).first().json["amount"])
    }

    @Test
    fun `filter keeps matching items only`() = runBlocking {
        val result = FilterNode().execute(
            ctx(
                stepOf(
                    "data.filter",
                    params = mapOf("conditions" to listOf(mapOf("left" to "{{ \$json.tags }}", "operator" to "Contains", "right" to "food"))),
                ),
                listOf(item("tags" to "food,coffee"), item("tags" to "travel")),
            )
        )
        assertEquals(1, result.port(0).size)
    }

    @Test
    fun `switch routes into the matching branch and else`() = runBlocking {
        val result = SwitchNode().execute(
            ctx(
                stepOf("flow.switch", params = mapOf("field" to "{{ \$json.type }}", "case1" to "invoice", "case2" to "receipt")),
                listOf(item("type" to "invoice"), item("type" to "receipt"), item("type" to "note")),
            )
        )
        assertEquals(1, result.port(0).size)
        assertEquals(1, result.port(1).size)
        assertEquals(1, result.port(4).size)
    }

    @Test
    fun `dedupe removes repeats by field`() = runBlocking {
        val result = DedupeNode().execute(
            ctx(
                stepOf("data.dedupe", params = mapOf("fields" to "id")),
                listOf(item("id" to 1), item("id" to 1), item("id" to 2)),
            )
        )
        assertEquals(2, result.port(0).size)
    }

    @Test
    fun `merge combines two branches from step outputs`() = runBlocking {
        val mergeStep = StepNode(
            id = "m", type = "flow.merge", name = "M",
            params = mapOf("mode" to "zip").toImmutableMap(),
        )
        val flow = com.kaivoflow.core.model.Flow(
            id = "f",
            name = "flow",
            steps = kotlinx.collections.immutable.persistentListOf(
                StepNode("a", "data.set", "A", y = 0f),
                StepNode("m", "flow.merge", "M", y = 10f),
                StepNode("b", "data.set", "B", y = 0f),
            ),
            streams = kotlinx.collections.immutable.persistentListOf(
                com.kaivoflow.core.model.StreamEdge("s1", "a", "m"),
                com.kaivoflow.core.model.StreamEdge("s2", "b", "m"),
            ),
        )
        val result = MergeNode().execute(
            ctx(
                mergeStep,
                listOf(item("left" to 1), item("left" to 2)),
                flow = flow,
                stepOutputs = mapOf(
                    "A" to listOf(item("left" to 1), item("left" to 2)),
                    "B" to listOf(item("right" to 10), item("right" to 20)),
                ),
            )
        )
        assertEquals(2, result.port(0).size)
        assertEquals(1, result.port(0)[0].json["left"])
        assertEquals(10, result.port(0)[0].json["right"])
    }

    @Test
    fun `loop respects its safety limit`() = runBlocking {
        val many = (1..20).map { item("n" to it) }
        val error = runCatching {
            LoopNode().execute(ctx(stepOf("flow.loop", params = mapOf("maxIterations" to 5)), many))
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("safety limit"))
    }

    // ---------------------------------------------------------------- transform
    @Test
    fun `set fields resolves expressions per item and keeps other fields`() = runBlocking {
        val result = SetFieldsNode().execute(
            ctx(
                stepOf(
                    "data.set",
                    params = mapOf(
                        "fields" to mapOf("label" to "{{ \$json.merchant }}", "amount" to 10),
                        "keepOtherFields" to true,
                    ),
                ),
                listOf(item("merchant" to "Cafe")),
            )
        )
        assertEquals("Cafe", result.port(0)[0].json["label"])
        assertEquals(10, result.port(0)[0].json["amount"])
    }

    @Test
    fun `json node parses text and unwraps arrays`() = runBlocking {
        val result = JsonNode().execute(
            ctx(
                stepOf("transform.json", params = mapOf("action" to "parse", "source" to "body", "unwrapArray" to true)),
                listOf(item("body" to """[{"a":1},{"a":2}]""")),
            )
        )
        assertEquals(2, result.port(0).size)
        assertEquals(2.0, (result.port(0)[1].json["a"] as Number).toDouble(), 0.001)
    }

    @Test
    fun `json node reports invalid json clearly`() = runBlocking {
        val error = runCatching {
            JsonNode().execute(
                ctx(stepOf("transform.json", params = mapOf("action" to "parse", "source" to "body")), listOf(item("body" to "{oops")))
            )
        }.exceptionOrNull()
        assertTrue(error is StepExecutionException)
        assertTrue(error!!.message!!.contains("valid JSON"))
    }

    @Test
    fun `aggregate groups and totals`() = runBlocking {
        val result = AggregateNode().execute(
            ctx(
                stepOf("transform.aggregate", params = mapOf("groupBy" to "merchant", "sumField" to "amount")),
                listOf(
                    item("merchant" to "Cafe", "amount" to 100),
                    item("merchant" to "Cafe", "amount" to 50),
                    item("merchant" to "Uber", "amount" to 200),
                ),
            )
        )
        val cafe = result.port(0).first { it.json["merchant"] == "Cafe" }
        assertEquals(2, cafe.json["count"])
        assertEquals(150.0, (cafe.json["total"] as Number).toDouble(), 0.001)
    }

    @Test
    fun `date node formats using the flow timezone`() = runBlocking {
        val result = DateNode().execute(
            ctx(
                stepOf(
                    "data.date",
                    params = mapOf("action" to "format", "source" to "2026-11-13T04:30:00Z", "pattern" to "dd MMM yyyy HH:mm"),
                ),
                listOf(item()),
            )
        )
        // 04:30 UTC is 10:00 in Asia/Kolkata.
        assertTrue((result.port(0)[0].json["date"] as String).endsWith("10:00"))
    }

    @Test
    fun `fields node renames removes and keeps`() = runBlocking {
        val result = FieldsNode().execute(
            ctx(
                stepOf("transform.fields", params = mapOf("rename" to mapOf("old" to "new"), "remove" to "junk")),
                listOf(item("old" to "v", "junk" to 1, "keep" to 2)),
            )
        )
        val json = result.port(0)[0].json
        assertEquals("v", json["new"])
        assertNull(json["old"])
        assertNull(json["junk"])
        assertEquals(2, json["keep"])
    }

    // ---------------------------------------------------------------- AI
    @Test
    fun `ai chat builds an openai-compatible request and stores the answer`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(
                status = 200,
                body = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "Spent 1250 on coffee"))), "usage" to mapOf("total_tokens" to 42)),
            )
        }
        val result = AiChatNode().execute(
            ctx(
                stepOf(
                    "ai.chat",
                    params = mapOf(
                        "provider" to "openai",
                        "credential" to "cred-1",
                        "model" to "gpt-4o-mini",
                        "prompt" to "Summarise: {{ \$json.text }}",
                        "outputField" to "answer",
                    ),
                ),
                listOf(item("text" to "debited 1250 at cafe")),
                http = http,
            )
        )
        val request = http.requests.single()
        assertEquals("https://api.openai.com/v1/chat/completions", request.url)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("gpt-4o-mini", Values.deepGet(request.body, "model"))
        assertEquals("Spent 1250 on coffee", result.port(0)[0].json["answer"])
    }

    @Test
    fun `ai chat supports gemini shape`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(
                status = 200,
                body = mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(mapOf("text" to "hello")))))),
            )
        }
        val result = AiChatNode().execute(
            ctx(
                stepOf(
                    "ai.chat",
                    params = mapOf("provider" to "gemini", "credential" to "cred-1", "model" to "gemini-2.0-flash", "prompt" to "hi"),
                ),
                listOf(item()),
                http = http,
            )
        )
        assertTrue(http.requests.single().url.contains("generativelanguage.googleapis.com"))
        assertEquals("hello", result.port(0)[0].json["answer"])
    }

    @Test
    fun `ai chat supports ollama without a key`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("message" to mapOf("content" to "local answer"))) }
        val result = AiChatNode().execute(
            ctx(
                stepOf("ai.chat", params = mapOf("provider" to "ollama", "model" to "llama3.2", "prompt" to "hi")),
                listOf(item()),
                http = http,
            )
        )
        assertTrue(http.requests.single().url.endsWith("/api/chat"))
        assertEquals("local answer", result.port(0)[0].json["answer"])
    }

    @Test
    fun `ai extract merges parsed json into the item`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(
                status = 200,
                body = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to """{"merchant":"Cafe","amount":1250.5}""")))),
            )
        }
        val result = AiExtractNode().execute(
            ctx(
                stepOf(
                    "ai.extract",
                    params = mapOf(
                        "credential" to "cred-1",
                        "model" to "gpt-4o-mini",
                        "text" to "{{ \$json.text }}",
                        "schema" to mapOf("merchant" to "string", "amount" to "number"),
                    ),
                ),
                listOf(item("text" to "debited 1250.50 at Cafe")),
                http = http,
            )
        )
        assertEquals("Cafe", result.port(0)[0].json["merchant"])
        assertEquals(1250.5, (result.port(0)[0].json["amount"] as Number).toDouble(), 0.001)
        assertEquals("debited 1250.50 at Cafe", result.port(0)[0].json["text"])
    }

    @Test
    fun `ai extract fails clearly when the model returns junk`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(status = 200, body = mapOf("choices" to listOf(mapOf("message" to mapOf("content" to "I cannot help")))))
        }
        val error = runCatching {
            AiExtractNode().execute(
                ctx(
                    stepOf(
                        "ai.extract",
                        params = mapOf("credential" to "cred-1", "model" to "m", "text" to "x", "schema" to mapOf("a" to "string")),
                    ),
                    listOf(item()),
                    http = http,
                )
            )
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("valid JSON"))
    }

    // ---------------------------------------------------------------- SaaS
    @Test
    fun `telegram node posts with the bot token and parse mode`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("result" to mapOf("message_id" to 7))) }
        val result = TelegramNode().execute(
            ctx(
                stepOf(
                    "app.telegram",
                    params = mapOf("credential" to "cred-1", "chatId" to "@chan", "text" to "Spent {{ \$json.amount }}", "parseMode" to "HTML"),
                ),
                listOf(item("amount" to 99)),
                http = http,
            )
        )
        val request = http.requests.single()
        assertTrue(request.url.contains("test-token"))
        assertEquals("@chan", Values.deepGet(request.body, "chat_id"))
        assertEquals("Spent 99", Values.deepGet(request.body, "text"))
        assertEquals("HTML", Values.deepGet(request.body, "parse_mode"))
        assertEquals(7, result.port(0)[0].json["telegramMessageId"])
    }

    @Test
    fun `google sheets append sends rows in column order`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("updates" to mapOf("updatedRows" to 1))) }
        GoogleSheetsNode().execute(
            ctx(
                stepOf(
                    "app.google_sheets",
                    params = mapOf(
                        "action" to "append",
                        "credential" to "cred-1",
                        "spreadsheetId" to "sheet-1",
                        "range" to "Sheet1!A1",
                        "columns" to "date, merchant, amount",
                    ),
                ),
                listOf(item("date" to "2026-11-13", "merchant" to "Cafe", "amount" to 100)),
                http = http,
            )
        )
        val request = http.requests.single()
        assertEquals(listOf(listOf("2026-11-13", "Cafe", "100")), Values.deepGet(request.body, "values"))
        assertEquals("Bearer test-token", request.headers["Authorization"])
    }

    @Test
    fun `google sheets read maps headers to fields`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(
                status = 200,
                body = mapOf("values" to listOf(listOf("date", "amount"), listOf("2026-11-13", "100"))),
            )
        }
        val result = GoogleSheetsNode().execute(
            ctx(
                stepOf("app.google_sheets", params = mapOf("action" to "read", "credential" to "cred-1", "spreadsheetId" to "s", "range" to "A1")),
                listOf(item()),
                http = http,
            )
        )
        assertEquals("2026-11-13", result.port(0).single().json["date"])
        assertEquals("100", result.port(0).single().json["amount"])
    }

    // ---------------------------------------------------------------- device
    @Test
    fun `notification node delegates to the gateway`() = runBlocking {
        val devices = RecordingDevices()
        NotificationSendNode(devices).execute(
            ctx(
                stepOf("device.notify", params = mapOf("title" to "Spend", "body" to "{{ \$json.total }} INR", "channel" to "urgent")),
                listOf(item("total" to 1200)),
                devices = devices,
            )
        )
        assertEquals("Spend", devices.notifications.single().first)
        assertEquals("1200 INR", devices.notifications.single().second)
        assertEquals("urgent", devices.notifications.single().third)
    }

    @Test
    fun `sms node refuses when permission is missing`() = runBlocking {
        val devices = RecordingDevices().apply { smsAllowed = false }
        val error = runCatching {
            SmsSendNode(devices).execute(
                ctx(stepOf("device.sms", params = mapOf("to" to "+91123", "body" to "hi")), listOf(item()), devices = devices)
            )
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("permission"))
        assertTrue(devices.sms.isEmpty())
    }

    @Test
    fun `file node writes then reads json`() = runBlocking {
        val devices = RecordingDevices()
        FileNode(devices).execute(
            ctx(stepOf("device.file", params = mapOf("action" to "write", "fileName" to "state.json", "content" to "{\"count\":3}")),
                listOf(item()), devices = devices)
        )
        val read = FileNode(devices).execute(
            ctx(stepOf("device.file", params = mapOf("action" to "read", "fileName" to "state.json")), listOf(item()), devices = devices)
        )
        assertEquals(3.0, (Values.deepGet(read.port(0).single().json, "content.count") as Number).toDouble(), 0.001)
    }

    // ---------------------------------------------------------------- triggers
    @Test
    fun `schedule trigger rejects an impossible time`() = runBlocking {
        val error = runCatching {
            ScheduleTriggerNode().execute(
                ctx(stepOf("spark.schedule", params = mapOf("frequency" to "daily", "time" to "25:99")), listOf(item()))
            )
        }.exceptionOrNull()
        assertTrue(error is StepExecutionException)
        assertTrue(error!!.message!!.contains("HH:MM"))
    }

    @Test
    fun `triggers pass run items through`() = runBlocking {
        val result = ManualTriggerNode().execute(ctx(stepOf("spark.manual"), listOf(item("a" to 1))))
        assertEquals(1, result.port(0).size)
    }

    @Test
    fun `code node returns whatever the script produces`() = runBlocking {
        val node = CodeNode()
        val scripted = object : com.kaivoflow.core.engine.ExpressionEvaluator {
            override fun evaluate(code: String, scope: Map<String, Any?>): Any? =
                listOf(mapOf("doubled" to 20))
        }
        val result = node.execute(
            ctx(
                stepOf("code.javascript", params = mapOf("mode" to "all", "code" to "return 1")),
                listOf(item("amount" to 10)),
                evaluator = scripted,
            )
        )
        assertEquals(20, result.port(0).single().json["doubled"])
    }
}
