package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec

/** Replays scripted answers and records every request. Never touches a network. */
class ScriptedModel(private val steps: MutableList<(ChatRequest) -> ChatResponse>) : ChatModelClient {
    val requests = mutableListOf<ChatRequest>()
    constructor(vararg responses: ChatResponse) : this(responses.map { r -> { _: ChatRequest -> r } }.toMutableList())

    override suspend fun complete(request: ChatRequest): ChatResponse {
        requests += request
        if (steps.isEmpty()) error("script exhausted after ${requests.size - 1} calls")
        return steps.removeAt(0)(request)
    }
}

fun say(text: String, tokens: Long = 10) = ChatResponse(text, usage = Usage(tokens, tokens))
fun call(name: String, args: Map<String, Any?> = emptyMap(), id: String = "c-$name", text: String = "", err: String? = null, tokens: Long = 10) =
    ChatResponse(text, listOf(ToolCall(id, name, args, err)), Usage(tokens, tokens), FinishReason.ToolCalls)

class FakeGateway(var responder: (HttpRequestSpec) -> HttpResponseSpec = { HttpResponseSpec(200, body = emptyMap<String, Any?>()) }) : HttpGateway {
    val requests = mutableListOf<HttpRequestSpec>()
    override suspend fun request(request: HttpRequestSpec): HttpResponseSpec { requests += request; return responder(request) }
}

class FakeTool(
    override val name: String,
    override val risk: ToolRisk = ToolRisk.Read,
    override val parameters: Map<String, Any?> = mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string")), "required" to listOf("q")),
    private val body: suspend (Map<String, Any?>) -> ToolOutcome = { ToolOutcome("result for ${it["q"]}") },
) : AgentTool {
    override val description = "fake $name"
    var calls = 0
    override suspend fun execute(arguments: Map<String, Any?>): ToolOutcome { calls++; return body(arguments) }
}
