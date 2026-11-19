package com.kaivoflow.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class AgentLimits(
    val maxIterations: Int = 10,
    val maxToolCalls: Int = 25,
    val maxTotalTokens: Long = 200_000,
    /** In the unit of [CostRates]; null means no cost cap. */
    val maxCostUnits: Double? = null,
    val maxWallMs: Long = 180_000,
    val perToolTimeoutMs: Long = 30_000,
    val maxOutputTokens: Int = 1_024,
    val maxToolResultChars: Int = 8_000,
    /** Soft cap on the history sent to the model; older tool results are replaced by a stub beyond it. */
    val maxContextChars: Int = 120_000,
    val maxConsecutiveToolErrors: Int = 4,
) {
    init {
        require(maxIterations in 1..100) { "maxIterations must be 1 to 100" }
        require(maxToolCalls >= 0 && maxWallMs > 0 && perToolTimeoutMs > 0 && maxOutputTokens > 0) { "limits must be positive" }
    }
}

/** Price per 1000 tokens in any currency unit the owner likes. An estimate, never an invoice. */
data class CostRates(val promptPer1k: Double, val completionPer1k: Double) {
    fun cost(u: Usage): Double = u.promptTokens / 1000.0 * promptPer1k + u.completionTokens / 1000.0 * completionPer1k
}

enum class AgentOutcome { Completed, IterationLimit, ToolCallLimit, TokenLimit, CostLimit, Timeout, ModelFailed, OutputInvalid, TooManyToolErrors }

data class AgentError(val outcome: AgentOutcome, val code: String, val message: String)

enum class ToolStatus { Ok, ToolError, Denied, Unknown, BadArguments, TimedOut }

/** One tool call as recorded for the Run log. [detail] is redacted and short. */
data class ToolTrace(val iteration: Int, val tool: String, val risk: ToolRisk?, val status: ToolStatus, val detail: String)

data class AgentResult(
    val outcome: AgentOutcome,
    val text: String,
    val structured: Any?,
    val usage: Usage,
    val iterations: Int,
    val toolCalls: Int,
    val trace: List<ToolTrace>,
    val estimatedCost: Double?,
    val error: AgentError?,
) {
    val isSuccess: Boolean get() = outcome == AgentOutcome.Completed
}

/**
 * The tool-calling loop: model, tool calls, results, model, until a plain answer or a limit.
 *
 * Rules it enforces: hard limits on iterations, tool calls, tokens, cost and wall time; a timeout
 * per tool; cancellation always propagates (never turned into a fallback answer); tool failures and
 * malformed model arguments go back to the model as text instead of crashing; tool results are
 * redacted, truncated and fenced as untrusted data; history is bounded; only the final answer is
 * remembered. It never throws for provider or tool trouble, it returns an [AgentResult] with a
 * structured [AgentError]; only [CancellationException] escapes.
 */
class AgentLoop(
    private val model: ChatModelClient,
    private val tools: ToolRegistry = ToolRegistry(emptyList()),
    private val policy: ApprovalPolicy = ApprovalPolicy(ApprovalHost.Unattended),
    private val limits: AgentLimits = AgentLimits(),
    private val memory: ConversationMemory? = null,
    private val parser: StructuredOutputParser? = null,
    private val redactor: Redactor = Redactor(),
    private val costRates: CostRates? = null,
    private val temperature: Double? = null,
    private val nonceSource: () -> String = UntrustedData::newNonce,
) {
    private class State {
        var iterations = 0
        var toolCalls = 0
        var usage = Usage()
        var lastText = ""
        val trace = ArrayList<ToolTrace>()
    }

    /**
     * Runs one task. [untrustedData] (Items, webhook or SMS text) is fenced as data and appended to
     * the user message; [instructions] and [task] are the Flow author's and the user's own words.
     */
    suspend fun run(instructions: String?, task: String, untrustedData: String? = null, memoryKey: String = "default"): AgentResult {
        val state = State()
        var result: AgentResult? = null
        val finished = withTimeoutOrNull(limits.maxWallMs) {
            result = loop(state, instructions, task, untrustedData, memoryKey)
            true
        }
        return if (finished == null) {
            finish(state, AgentOutcome.Timeout, state.lastText, null, AgentError(AgentOutcome.Timeout, "timeout", "The agent ran past its time limit of ${limits.maxWallMs / 1000}s."))
        } else result!!
    }

    private suspend fun loop(state: State, instructions: String?, task: String, untrustedData: String?, memoryKey: String): AgentResult {
        val nonce = nonceSource()
        val offered = tools.offered(policy)
        val specs = tools.specs(policy)
        val system = SystemPrompt.build(instructions, parser?.schema, nonce, hasTools = specs.isNotEmpty())
        val userText = if (untrustedData.isNullOrBlank()) task else "$task\n\n${UntrustedData.wrap("input_data", untrustedData, nonce)}"
        val history = ArrayList<ChatMessage>(memory?.load(memoryKey).orEmpty())
        history += ChatMessage.user(userText)
        var toolErrorStreak = 0

        while (true) {
            coroutineContext.ensureActive()
            if (state.iterations >= limits.maxIterations) {
                return fail(state, AgentOutcome.IterationLimit, "iteration_limit", "The agent did not finish within ${limits.maxIterations} steps.")
            }
            state.iterations++
            compact(history)
            val resp = try {
                model.complete(ChatRequest(system, history.toList(), specs, temperature, limits.maxOutputTokens))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                return fail(state, AgentOutcome.ModelFailed, "model_${e.kind.name.lowercase()}", redactor.redact(e.message.orEmpty()))
            } catch (e: Throwable) {
                return fail(state, AgentOutcome.ModelFailed, "model_error", "The model call failed: ${redactor.redact(e.message ?: e::class.java.simpleName).take(200)}")
            }
            state.usage += resp.usage
            if (resp.text.isNotBlank()) state.lastText = resp.text

            if (resp.toolCalls.isEmpty()) {
                if (resp.text.isBlank()) return fail(state, AgentOutcome.ModelFailed, "model_empty", "The model returned no answer.")
                return conclude(state, resp.text.trim(), task, memoryKey)
            }

            history += ChatMessage.assistant(resp.text, resp.toolCalls)
            for (call in resp.toolCalls) {
                coroutineContext.ensureActive()
                if (state.toolCalls >= limits.maxToolCalls) {
                    return fail(state, AgentOutcome.ToolCallLimit, "tool_call_limit", "The agent asked for more than ${limits.maxToolCalls} tool calls.")
                }
                state.toolCalls++
                val (text, status, wrap) = runTool(state, call, offered)
                toolErrorStreak = if (status == ToolStatus.Ok) 0 else toolErrorStreak + 1
                history += ChatMessage.tool(call, if (wrap) UntrustedData.wrap("tool:${call.name}", text, nonce) else text, isError = status != ToolStatus.Ok)
                if (toolErrorStreak >= limits.maxConsecutiveToolErrors) {
                    return fail(state, AgentOutcome.TooManyToolErrors, "tool_errors", "The agent kept failing to use its tools ($toolErrorStreak failures in a row).")
                }
            }
            if (state.usage.totalTokens > limits.maxTotalTokens) {
                return fail(state, AgentOutcome.TokenLimit, "token_limit", "The agent used more than ${limits.maxTotalTokens} tokens.")
            }
            val cost = costRates?.cost(state.usage)
            if (cost != null && limits.maxCostUnits != null && cost > limits.maxCostUnits) {
                return fail(state, AgentOutcome.CostLimit, "cost_limit", "The estimated cost passed the limit of ${limits.maxCostUnits}.")
            }
        }
    }

    private data class ToolRun(val text: String, val status: ToolStatus, val wrap: Boolean)

    private suspend fun runTool(state: State, call: ToolCall, offered: List<AgentTool>): ToolRun {
        fun record(tool: AgentTool?, status: ToolStatus, detail: String) {
            state.trace += ToolTrace(state.iterations, call.name, tool?.risk, status, redactor.redact(detail).take(200))
        }
        if (call.argumentsError != null) {
            record(null, ToolStatus.BadArguments, call.argumentsError)
            return ToolRun("The arguments for ${call.name} were not a valid JSON object (${call.argumentsError}). Send a JSON object that matches the tool schema.", ToolStatus.BadArguments, false)
        }
        val tool = offered.firstOrNull { it.name == call.name }
        if (tool == null) {
            record(null, ToolStatus.Unknown, "unknown tool")
            return ToolRun("There is no tool named \"${call.name.take(64)}\". Available tools: ${offered.joinToString { it.name }.ifEmpty { "none" }}.", ToolStatus.Unknown, false)
        }
        val schemaErrors = SchemaCheck.validate(tool.parameters, call.arguments)
        if (schemaErrors.isNotEmpty()) {
            record(tool, ToolStatus.BadArguments, schemaErrors.first())
            return ToolRun("The arguments for ${tool.name} are not valid: ${schemaErrors.joinToString("; ")}. Fix them and try again.", ToolStatus.BadArguments, false)
        }
        when (val d = policy.decide(tool, call.arguments)) {
            is ApprovalDecision.Denied -> {
                record(tool, ToolStatus.Denied, d.reason)
                return ToolRun("Not allowed: ${d.reason}. Do not call ${tool.name} again for this task; continue without it or explain what you could not do.", ToolStatus.Denied, false)
            }
            ApprovalDecision.Approved -> Unit
        }
        val outcome = try {
            withTimeoutOrNull(limits.perToolTimeoutMs) { tool.execute(call.arguments) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolOutcome("the tool failed: ${e.message ?: e::class.java.simpleName}", isError = true)
        }
        if (outcome == null) {
            record(tool, ToolStatus.TimedOut, "timed out after ${limits.perToolTimeoutMs} ms")
            return ToolRun("${tool.name} did not answer within ${limits.perToolTimeoutMs / 1000.0}s.", ToolStatus.TimedOut, false)
        }
        var text = redactor.redact(outcome.text)
        if (text.length > limits.maxToolResultChars) text = text.take(limits.maxToolResultChars) + "…[truncated]"
        val status = if (outcome.isError) ToolStatus.ToolError else ToolStatus.Ok
        record(tool, status, if (outcome.isError) text else "ok, ${text.length} chars")
        return ToolRun(text, status, wrap = true)
    }

    private suspend fun conclude(state: State, text: String, task: String, memoryKey: String): AgentResult {
        var structured: Any? = null
        var finalText = text
        if (parser != null) {
            val outcome = parser.parseWithRepair(model, text, maxOutputTokens = limits.maxOutputTokens)
            state.usage += outcome.usage
            if (!outcome.ok) {
                return fail(state, AgentOutcome.OutputInvalid, "output_invalid", "The answer did not match the required format: ${outcome.errors.take(3).joinToString("; ")}")
            }
            structured = outcome.value
            finalText = J.write(structured)
        }
        memory?.remember(memoryKey, task, finalText)
        return finish(state, AgentOutcome.Completed, finalText, structured, null)
    }

    private fun fail(state: State, outcome: AgentOutcome, code: String, message: String) =
        finish(state, outcome, state.lastText, null, AgentError(outcome, code, message))

    private fun finish(state: State, outcome: AgentOutcome, text: String, structured: Any?, error: AgentError?) = AgentResult(
        outcome = outcome, text = text, structured = structured, usage = state.usage,
        iterations = state.iterations, toolCalls = state.toolCalls, trace = state.trace.toList(),
        estimatedCost = costRates?.cost(state.usage), error = error,
    )

    /** Keeps the history under [AgentLimits.maxContextChars] by stubbing the oldest tool results first. */
    private fun compact(history: MutableList<ChatMessage>) {
        fun size() = history.sumOf { it.text.length + it.toolCalls.sumOf { c -> J.write(c.arguments).length } }
        var i = 0
        while (size() > limits.maxContextChars && i < history.size - 1) {
            val m = history[i]
            if (m.role == Role.Tool && m.text != STUB) history[i] = m.copy(text = STUB)
            i++
        }
    }

    private companion object { const val STUB = "[earlier tool result omitted to save space]" }
}
