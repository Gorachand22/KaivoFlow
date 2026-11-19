package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsAndSafetyTest {
    private fun spec(id: String, vararg params: ParamSpec, kind: NodeKind = NodeKind.Action, role: NodeRole = NodeRole.Main, lifecycle: Lifecycle = Lifecycle.Stable) =
        NodeSpec(id, "T", StepCategory.Device, "s", "i", kind = kind, role = role, lifecycle = lifecycle, params = params.toList().toImmutableList())

    private val smsSpec = spec(
        "device.sms",
        ParamSpec("to", "Phone number", ParamType.Text, required = true),
        ParamSpec("body", "Message", ParamType.Multiline, required = true),
        ParamSpec("credential", "Cred", ParamType.Secret),
        ParamSpec("mode", "Mode", ParamType.Select, default = "a", options = persistentListOf(ParamOption("a", "A"), ParamOption("b", "B"))),
        ParamSpec("hidden", "Hidden", ParamType.Text, advanced = true),
        ParamSpec("count", "Count", ParamType.Number),
        ParamSpec("flag", "Flag", ParamType.Boolean),
        ParamSpec("extra", "Extra", ParamType.Json),
    )

    @Test fun `schema is generated from the spec and leaves secrets and advanced params out`() {
        val s = StepTools.schema(smsSpec)
        val props = s["properties"] as Map<*, *>
        assertEquals(setOf("to", "body", "mode", "count", "flag", "extra"), props.keys)
        assertEquals("string", (props["to"] as Map<*, *>)["type"]); assertEquals("number", (props["count"] as Map<*, *>)["type"])
        assertEquals("boolean", (props["flag"] as Map<*, *>)["type"]); assertEquals("object", (props["extra"] as Map<*, *>)["type"])
        assertEquals(listOf("a", "b"), (props["mode"] as Map<*, *>)["enum"])
        assertEquals(listOf("to", "body"), s["required"]); assertEquals(false, s["additionalProperties"])
        assertFalse(StepTools.schema(smsSpec, locked = setOf("to"))["properties"].let { (it as Map<*, *>).containsKey("to") })
        assertEquals(listOf("body"), StepTools.schema(smsSpec, locked = setOf("to"))["required"])
    }

    @Test fun `offerable steps exclude triggers, flow control, ai, hidden and sub steps`() {
        assertTrue(StepTools.isOfferable(smsSpec))
        assertFalse(StepTools.isOfferable(spec("spark.manual", kind = NodeKind.Trigger)))
        assertFalse(StepTools.isOfferable(spec("flow.wait"))); assertFalse(StepTools.isOfferable(spec("ai.agent")))
        assertFalse(StepTools.isOfferable(spec("transform.csv", lifecycle = Lifecycle.Hidden)))
        assertFalse(StepTools.isOfferable(spec("x.model", role = NodeRole.AiModel)))
    }

    @Test fun `risk classes use the real catalog ids and fail safe`() {
        assertEquals(ToolRisk.Read, StepTools.riskOf("data.filter"))
        assertEquals(ToolRisk.Read, StepTools.riskOf("device.battery_status"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("device.sms"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("app.telegram"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("code.javascript"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("device.location"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("code.script")) // an id that does not exist is never trusted as read
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("net.http"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("net.http", mapOf("method" to "POST", "url" to "https://a.example")))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("net.http", mapOf("method" to "GET")))
        assertEquals(ToolRisk.Read, StepTools.riskOf("net.http", mapOf("method" to "GET", "url" to "https://a.example/x")))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("net.http", mapOf("method" to "GET", "url" to "{{ \$json.u }}")))
        assertEquals(ToolRisk.Destructive, StepTools.riskOf("device.file"))
        assertEquals(ToolRisk.ExternalEffect, StepTools.riskOf("device.file", mapOf("action" to "read")))
    }

    @Test fun `neutralize stops model text from becoming an expression`() {
        assertEquals("a {​{ \$json.secret }}", StepTools.neutralize("a {{ \$json.secret }}"))
        val m = StepTools.neutralize(mapOf("kx" to "{{ 1 }}", "list" to listOf("{{x}}"))) as Map<*, *>
        assertTrue(m.containsKey("_kx")); assertFalse(m.containsKey("kx"))
        assertFalse((m["list"] as List<*>)[0].toString().contains("{{"))
    }

    private class RecordingExecutor(override val spec: NodeSpec, val fail: Boolean = false) : StepExecutor {
        var seen: StepNode? = null
        override suspend fun execute(ctx: StepContext): StepResult {
            seen = ctx.step
            if (fail) throw StepExecutionException(ctx.step.id, ctx.step.name, "denied by phone with token sk-abcdefghijklmnop")
            return ctx.output(listOf(FlowData(mapOf("sent" to ctx.step.params["to"], "body" to ctx.step.params["body"]))))
        }
    }

    private fun stepCtx(node: StepNode, input: Map<String, Any?>) = StepContext(
        step = node, flow = Flow(id = "f", name = "F"), input = listOf(FlowData(input)), runId = "r", mode = com.kaivoflow.core.engine.ExecutionMode.Manual,
        attempt = 1, evaluator = object : com.kaivoflow.core.engine.ExpressionEvaluator { override fun evaluate(code: String, scope: Map<String, Any?>): Any? = null },
        http = FakeGateway(), credentials = object : com.kaivoflow.core.engine.CredentialProvider { override suspend fun resolve(ref: String) = emptyMap<String, String>() },
        clock = { 0L }, timezone = "UTC", stepOutputs = emptyMap(), logger = { _, _ -> },
    )

    @Test fun `a step tool keeps author params, drops unknown keys and neutralises expressions`() = runBlocking {
        val exec = RecordingExecutor(smsSpec)
        val tool = StepTool("device_sms", "Send an SMS", exec, fixedParams = mapOf("to" to "+15550100", "credential" to "cred-1"), contextFor = ::stepCtx)
        assertEquals(ToolRisk.ExternalEffect, tool.risk)
        assertFalse((tool.parameters["properties"] as Map<*, *>).containsKey("to")) // locked by the author
        val out = tool.execute(mapOf("to" to "+66600", "body" to "hi {{ \$vars.secret }}", "credential" to "steal", "notInSchema" to 1))
        val p = exec.seen!!.params
        assertEquals("+15550100", p["to"]); assertEquals("cred-1", p["credential"]); assertFalse(p.containsKey("notInSchema"))
        assertFalse(p["body"].toString().contains("{{"))
        assertFalse(out.isError); assertTrue(out.text.startsWith("{")); assertEquals("device.sms", exec.seen!!.type)
    }

    @Test fun `step failures become redacted tool errors`() = runBlocking {
        val tool = StepTool("device_sms", "Send", RecordingExecutor(smsSpec, fail = true), contextFor = ::stepCtx)
        val out = tool.execute(mapOf("to" to "1", "body" to "x"))
        assertTrue(out.isError); assertFalse(out.text.contains("abcdefghijkl")); assertTrue(out.text.contains("denied by phone"))
    }

    @Test fun `registry validates names and duplicates`() {
        assertTrue(runCatching { ToolRegistry(listOf(FakeTool("bad name"))) }.isFailure)
        assertTrue(runCatching { ToolRegistry(listOf(FakeTool("a"), FakeTool("a"))) }.isFailure)
        assertEquals(listOf("a"), ToolRegistry(listOf(FakeTool("a"), FakeTool("b")), allow = setOf("a")).all.map { it.name })
        assertNull(ToolRegistry(listOf(FakeTool("a")), allow = emptySet())["a"])
    }

    @Test fun `redactor masks keys in text and in structures`() {
        val r = Redactor(listOf("my-vault-secret-value"))
        val t = r.redact("a my-vault-secret-value b sk-ant-api03-ABCDEFGHIJKLMN c AIzaSyA1234567890abcdefghijkl d Bearer abcdefghijklmnop e https://x/v?key=abcdef123456&z=1 f")
        assertFalse(t.contains("my-vault")); assertFalse(t.contains("ABCDEFG")); assertFalse(t.contains("AIzaSy")); assertFalse(t.contains("abcdefghijklmnop")); assertFalse(t.contains("abcdef123456"))
        assertTrue(t.contains("z=1"))
        val v = r.redactValue(mapOf("apiKey" to "plain", "nested" to listOf(mapOf("Authorization" to "Bearer zzzzzzzzzzzzzz")), "ok" to "fine")) as Map<*, *>
        assertEquals("***", v["apiKey"]); assertEquals("fine", v["ok"])
    }

    @Test fun `window memory trims on write, starts on a user turn and redacts`() = runBlocking {
        val store = InMemoryStore()
        val mem = WindowMemory(store, maxMessages = 4, maxCharsPerMessage = 20)
        repeat(5) { mem.remember("k", "question $it with sk-abcdefghijklmnop", "answer $it ".repeat(10)) }
        val stored = store.read("k")
        assertEquals(4, stored.size); assertEquals(Role.User, stored.first().role)
        assertTrue(stored.all { it.text.length <= 21 }); assertFalse(stored.joinToString { it.text }.contains("abcdefghijkl"))
        mem.clear("k"); assertTrue(mem.load("k").isEmpty())
        assertTrue(runCatching { WindowMemory(maxMessages = 1) }.isFailure)
    }

    @Test fun `output parser extracts json from fences and prose and validates the schema`() {
        val p = StructuredOutputParser(mapOf("type" to "object", "required" to listOf("n"), "properties" to mapOf("n" to mapOf("type" to "integer")), "additionalProperties" to false))
        assertTrue(p.parse("```json\n{\"n\": 2}\n```") is ParseResult.Ok)
        assertTrue(p.parse("Here you go: {\"n\": 2} hope that helps") is ParseResult.Ok)
        assertTrue(p.parse("{\"note\": 1} then {\"n\": 5}") is ParseResult.Ok) // the second candidate fits
        assertTrue(p.parse("{\"n\": \"x\"}") is ParseResult.Invalid)
        assertTrue(p.parse("{\"n\": 1, \"extra\": 2}") is ParseResult.Invalid)
        assertTrue(p.parse("nothing") is ParseResult.Invalid); assertTrue(p.parse("{\"n\": 1") is ParseResult.Invalid)
        assertNull(SchemaCheck.normalize("not json")); assertNull(SchemaCheck.normalize(mapOf("a" to "blob")))
        assertTrue(SchemaCheck.normalize("""{"a":"string"}""") != null)
    }

    @Test fun `an error path shows nested validation messages`() {
        val schema = mapOf("type" to "object", "properties" to mapOf("items" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 2)))
        val errs = SchemaCheck.validate(schema, mapOf("items" to listOf("a", 1, "c")))
        assertTrue(errs.any { it.contains("items[1]") }); assertTrue(errs.any { it.contains("at most 2") })
    }
}
