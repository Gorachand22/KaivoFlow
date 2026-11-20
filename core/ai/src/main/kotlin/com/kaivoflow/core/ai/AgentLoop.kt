package com.kaivoflow.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class AgentLimits(
    val maxIterations: Int = 10,
    val maxToolCalls: Int = 25,
    val maxTotalTokens: Long = 200_000,
    /** In the unit of [CostRates] when rates are given, else US dollars from the [PriceTable]; null means no cost cap. */
    val maxCostUnits: Double? = null,
    /** Working time of the run. Time spent waiting for a person to answer an approval card is not counted. */
    val maxWallMs: Long = 180_000,
    val perToolTimeoutMs: Long = 30_000,
    val maxOutputTokens: Int = 4_096,
    val maxToolResultChars: Int = 8_000,
    /** Soft cap on the history sent to the model; older tool results are replaced by a stub beyond it. */
    val maxContextChars: Int = 120_000,
    val maxConsecutiveToolErrors: Int = 4,
    /** How long a person may take to answer one approval card before it counts as a refusal. */
    val approvalTimeoutMs: Long = 600_000,
) {
    init {
        require(maxIterations in 1..100) { "maxIterations must be 1 to 100" }
        require(maxToolCalls >= 0 && maxWallMs > 0 && perToolTimeoutMs > 0 && maxOutputTokens > 0 && approvalTimeoutMs > 0) { "limits must be positive" }
    }
}

/** Price per 1000 tokens in any currency unit the owner likes. An estimate, never an invoice. */
data class CostRates(val promptPer1k: Double, val completionPer1k: Double) {
    fun cost(u: Usage): Double = u.promptTokens / 1000.0 * promptPer1k + u.completionTokens / 1000.0 * completionPer1k
}

enum class AgentOutcome { Completed, IterationLimit, ToolCallLimit, TokenLimit, CostLimit, Timeout, ModelFailed, OutputInvalid, TooManyToolErrors }

/** [modelErrorKind], [status], [retryable], [hint] and [attempts] are set when a model call caused the failure. */
data class AgentError(
    val outcome: AgentOutcome,
    val code: String,
    val message: String,
    val modelErrorKind: ModelErrorKind? = null,
    val status: Int? = null,
    val retryable: Boolean = false,
    val hint: String? = null,
    val attempts: Int = 1,
)

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
    /** Cost of the run in the unit of [CostRates] or US dollars; null when no price is known. See [cost]. */
    val estimatedCost: Double?,
    val error: AgentError?,
    /** True when the final answer was cut by the output limit even after one continue call. */
    val truncated: Boolean = false,
    /** True when a model other than the first of the chain answered at least one call. */
    val fallbackUsed: Boolean = false,
    val usageByModel: Map<String, Usage> = emptyMap(),
    val cost: CostEstimate? = null,
) {
    val isSuccess: Boolean get() = outcome == AgentOutcome.Completed
}

/**
 * The tool-calling loop: model, tool calls, results, model, until a plain answer or a limit.
 *
 * Rules it enforces: hard limits on iterations, tool calls, tokens, cost and working time (the time a person takes to
 * answer an approval is not working time); a timeout per tool; cancellation always propagates (never turned into a
 * fallback answer or a fallback model); tool failures and malformed model arguments go back to the model as text
 * instead of crashing; tool results are redacted, truncated and fenced as untrusted data; history is bounded; only
 * the final answer is remembered. It never throws for provider or tool trouble, it returns an [AgentResult] with a
 * structured [AgentError]; only [CancellationException] escapes.
 *
 * Progress: [run] takes an optional event callback and [runStreaming] returns the same events as a Flow; both use
 * real provider streaming when the client supports it. Collecting the flow is what runs the agent, and cancelling
 * the collection cancels the model call and any tool.
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
    /** Tried in order when [model] fails after its own retries. Never tried after a cancellation. */
    private val fallbacks: List<ChatModelClient> = emptyList(),
    /** The first model call must use a tool (when real tools are offered). Later calls are free. */
    private val forceToolFirst: Boolean = false,
    private val reasoning: ReasoningConfig? = null,
    /** Prices for cost when [costRates] is null. Null disables price lookup. */
    private val priceTable: PriceTable? = PriceTable.BUNDLED,
    /** Optional shared cap (tokens, USD) that may span several runs. */
    private val budget: RunBudget? = null,
    /** Structured output through the hidden [FinalAnswer] tool, with text parsing as fallback. */
    private val structuredViaTool: Boolean = true,
    /** Ask the model once to continue when its answer is cut by the output limit. */
    private val continueOnLength: Boolean = true,
) {
    private val client: ChatModelClient = ChatModels.withFallbacks(model, fallbacks)

    private class WallClock(private val totalMs: Long) {
        private var spentNs = 0L
        private var since = System.nanoTime()
        private var paused = 0
        @Synchronized fun remainingMs(): Long {
            val running = if (paused == 0) System.nanoTime() - since else 0L
            return totalMs - (spentNs + running) / 1_000_000
        }
        @Synchronized fun pause() { if (paused++ == 0) { spentNs += System.nanoTime() - since } }
        @Synchronized fun resume() { if (paused > 0 && --paused == 0) since = System.nanoTime() }
    }

    private class State(val wall: WallClock, var sink: (suspend (AgentEvent) -> Unit)?) {
        var iterations = 0
        var toolCalls = 0
        var lastText = ""
        var timedOut = false
        var fallbackUsed = false
        var truncated = false
        var continues = 0
        val ledger = UsageLedger()
        val trace = ArrayList<ToolTrace>()
        val usage: Usage get() = ledger.total

        suspend fun emit(e: AgentEvent) {
            val s = sink ?: return
            try { s(e) } catch (c: CancellationException) { throw c } catch (_: Throwable) { sink = null }
        }
    }

    /** Events as a cold Flow: collecting runs the agent. The last event is [AgentEvent.Done] unless cancelled. */
    fun runStreaming(instructions: String?, task: String, untrustedData: String? = null, memoryKey: String = "default"): Flow<AgentEvent> =
        channelFlow { run(instructions, task, untrustedData, memoryKey) { send(it) } }.buffer(Channel.UNLIMITED)

    /**
     * Runs one task. [untrustedData] (Items, webhook or SMS text) is fenced as data and appended to
     * the user message; [instructions] and [task] are the Flow author's and the user's own words.
     * With [onEvent] the model is called through its streaming API and every step is reported.
     */
    suspend fun run(
        instructions: String?,
        task: String,
        untrustedData: String? = null,
        memoryKey: String = "default",
        onEvent: (suspend (AgentEvent) -> Unit)? = null,
    ): AgentResult = coroutineScope {
        val state = State(WallClock(limits.maxWallMs), onEvent)
        state.emit(AgentEvent.RunStarted)
        val worker = async { loop(state, instructions, task, untrustedData, memoryKey) }
        val watchdog = launch {
            while (true) {
                val rem = state.wall.remainingMs()
                if (rem <= 0) { state.timedOut = true; worker.cancel(); break }
                delay(rem.coerceIn(1, 1_000))
            }
        }
        val result: AgentResult = try {
            worker.await()
        } catch (e: CancellationException) {
            if (!state.timedOut) throw e
            finish(state, AgentOutcome.Timeout, state.lastText, null, AgentError(AgentOutcome.Timeout, "timeout", "The agent ran past its time limit of ${limits.maxWallMs / 1000}s."))
        } finally {
            watchdog.cancel()
        }
        result.error?.let { state.emit(AgentEvent.Error(it)) }
        state.emit(AgentEvent.Done(result))
        result
    }

    private suspend fun loop(state: State, instructions: String?, task: String, untrustedData: String?, memoryKey: String): AgentResult {
        val nonce = nonceSource()
        val offered = tools.offered(policy)
        val specs = tools.specs(policy)
        val finalSpec: ToolSpec? = if (parser != null && structuredViaTool && specs.none { it.name == FinalAnswer.TOOL_NAME }) FinalAnswer.spec(parser.schema) else null
        val system = SystemPrompt.build(instructions, parser?.schema, nonce, hasTools = specs.isNotEmpty(), finalAnswerTool = finalSpec != null)
        val userText = if (untrustedData.isNullOrBlank()) task else "$task\n\n${UntrustedData.wrap("input_data", untrustedData, nonce)}"
        val history = ArrayList<ChatMessage>(memory?.load(memoryKey).orEmpty())
        history += ChatMessage.user(userText)
        var toolErrorStreak = 0
        var carried = "" // answer text from earlier, cut-off steps

        while (true) {
            coroutineContext.ensureActive()
            if (state.iterations >= limits.maxIterations) {
                return fail(state, AgentOutcome.IterationLimit, "iteration_limit", "The agent did not finish within ${limits.maxIterations} steps.")
            }
            budgetStop(state)?.let { return it }
            state.iterations++
            compact(history)
            val first = state.iterations == 1
            val forced = forceToolFirst && first && specs.isNotEmpty()
            val callSpecs = if (forced || finalSpec == null) specs else specs + finalSpec
            val request = ChatRequest(
                system, history.toList(), callSpecs, temperature, limits.maxOutputTokens,
                toolChoice = if (forced) ToolChoice.Required else ToolChoice.Auto, reasoning = reasoning,
            )
            if (budget?.wouldExceed(TokenEstimator.forRequest(request)) == true) {
                return fail(state, AgentOutcome.TokenLimit, "token_limit", "Another model call would pass the token budget.")
            }
            state.emit(AgentEvent.StepStarted(state.iterations))
            val resp = try {
                callModel(state, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                return modelFailure(state, e)
            } catch (e: Throwable) {
                return fail(state, AgentOutcome.ModelFailed, "model_error", "The model call failed: ${redactor.redact(e.message ?: e::class.java.simpleName).take(200)}")
            }
            val used = TokenEstimator.usageOrEstimate(resp.usage, request, resp)
            account(state, resp.model, used)
            if (resp.servedBy > 0) state.fallbackUsed = true
            if (resp.text.isNotBlank()) state.lastText = carried + resp.text
            state.emit(AgentEvent.StepFinished(state.iterations, resp.finish, used, resp.model))
            state.emit(AgentEvent.UsageUpdate(state.usage, state.ledger.costEstimate))

            if (resp.toolCalls.isEmpty()) {
                if (resp.text.isBlank() && carried.isBlank()) {
                    val why = if (resp.finish == FinishReason.Length) "The model returned no answer; it probably ran out of output tokens (limit ${limits.maxOutputTokens}) while thinking." else "The model returned no answer."
                    return fail(state, AgentOutcome.ModelFailed, "model_empty", why)
                }
                if (resp.finish == FinishReason.Length) {
                    if (continueOnLength && state.continues < 1) {
                        state.continues++
                        carried += resp.text
                        state.emit(AgentEvent.Notice(NoticeKind.Continue, "The answer was cut by the output limit; asking the model to continue."))
                        history += ChatMessage.assistant(resp.text, providerData = resp.providerData)
                        history += ChatMessage.user("Your answer was cut off. Continue exactly where you stopped, without repeating anything.")
                        continue
                    }
                    state.truncated = true
                    state.emit(AgentEvent.Notice(NoticeKind.Truncated, "The answer is cut by the output limit."))
                }
                return conclude(state, (carried + resp.text).trim(), task, memoryKey)
            }

            history += ChatMessage.assistant(resp.text, resp.toolCalls, resp.providerData)
            val finalCall = if (finalSpec != null) resp.toolCalls.firstOrNull { it.name == FinalAnswer.TOOL_NAME } else null
            if (finalCall != null && resp.toolCalls.size == 1) {
                state.toolCalls++
                val adapted = ToolArguments.adapt(finalSpec!!.parameters, finalCall)
                val problems = if (adapted.argumentsError != null) listOf(adapted.argumentsError) else FinalAnswer.validate(parser!!.schema, adapted.arguments)
                if (problems.isEmpty()) {
                    state.trace += ToolTrace(state.iterations, FinalAnswer.TOOL_NAME, ToolRisk.Read, ToolStatus.Ok, "final answer")
                    val value = FinalAnswer.unwrap(parser!!.schema, adapted.arguments)
                    memory?.remember(memoryKey, task, J.write(value))
                    return finish(state, AgentOutcome.Completed, J.write(value), value, null)
                }
                state.trace += ToolTrace(state.iterations, FinalAnswer.TOOL_NAME, ToolRisk.Read, ToolStatus.BadArguments, redactor.redact(problems.first()).take(200))
                history += ChatMessage.tool(finalCall, ToolErrors.format("the final answer does not match the required format: ${problems.joinToString("; ")}. Fix it and call ${FinalAnswer.TOOL_NAME} again."), isError = true)
                toolErrorStreak++
                if (toolErrorStreak >= limits.maxConsecutiveToolErrors) {
                    return fail(state, AgentOutcome.OutputInvalid, "output_invalid", "The answer did not match the required format: ${problems.take(3).joinToString("; ")}")
                }
                continue
            }
            for (call in resp.toolCalls) {
                coroutineContext.ensureActive()
                if (call.name == FinalAnswer.TOOL_NAME && finalSpec != null) {
                    history += ChatMessage.tool(call, ToolErrors.format("call ${FinalAnswer.TOOL_NAME} on its own, after you have the results of the other tools."), isError = true)
                    continue
                }
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
        }
    }

    /** One model call. Streams (and reports) only when someone listens; otherwise a plain call. */
    private suspend fun callModel(state: State, request: ChatRequest): ChatResponse {
        if (state.sink == null) return client.complete(request)
        var done: ChatResponse? = null
        client.stream(request).collect { ev ->
            when (ev) {
                is StreamEvent.TextDelta -> state.emit(AgentEvent.TextDelta(ev.text))
                is StreamEvent.ThinkingDelta -> state.emit(AgentEvent.ThinkingDelta(ev.text))
                is StreamEvent.ToolCallStart -> state.emit(AgentEvent.ToolCallStarted(ev.index, ev.id, ev.name))
                is StreamEvent.ToolCallArgsDelta -> state.emit(AgentEvent.ToolCallArgsDelta(ev.index, redactor.redact(ev.delta)))
                is StreamEvent.ToolCallEnd -> state.emit(AgentEvent.ToolCallReady(ev.call.id, ev.call.name, (redactor.redactValue(ev.call.arguments) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()))
                is StreamEvent.UsageUpdate -> Unit
                is StreamEvent.Retrying -> {
                    state.emit(AgentEvent.Notice(NoticeKind.Retry, "The model call failed (${redactor.redact(ev.reason)}); retrying in ${ev.delayMs / 1000.0}s, attempt ${ev.retryNumber + 1}."))
                    if (ev.discardedOutput) state.emit(AgentEvent.StreamReset)
                }
                is StreamEvent.FallbackUsed -> {
                    state.emit(AgentEvent.Notice(NoticeKind.Fallback, "Model ${ev.fromIndex + 1} failed (${redactor.redact(ev.reason)}); using fallback model ${ev.toIndex + 1}."))
                    if (ev.discardedOutput) state.emit(AgentEvent.StreamReset)
                }
                is StreamEvent.Done -> done = ev.response
            }
        }
        return done ?: throw ModelException(ModelErrorKind.Malformed, "The model stream ended without an answer.")
    }

    private fun callCost(model: String?, usage: Usage): CostEstimate =
        costRates?.let { CostEstimate(it.cost(usage), usage.estimated) } ?: priceTable?.estimate(model, usage) ?: CostEstimate(null, usage.estimated, setOfNotNull(model))

    private fun account(state: State, model: String?, usage: Usage) {
        val c = callCost(model, usage)
        state.ledger.record(model, usage, c)
        budget?.add(usage, c)
    }

    /** Budget checks before a model call: the run's own token and cost limits, then the shared budget. */
    private fun budgetStop(state: State): AgentResult? {
        if (state.usage.totalTokens > limits.maxTotalTokens) return fail(state, AgentOutcome.TokenLimit, "token_limit", "The agent used more than ${limits.maxTotalTokens} tokens.")
        val cost = state.ledger.costEstimate.usd
        if (cost != null && limits.maxCostUnits != null && cost > limits.maxCostUnits) {
            return fail(state, AgentOutcome.CostLimit, "cost_limit", "The estimated cost passed the limit of ${limits.maxCostUnits}.")
        }
        return when (budget?.check()) {
            BudgetStop.Tokens -> fail(state, AgentOutcome.TokenLimit, "token_limit", "The token budget is used up.")
            BudgetStop.Cost -> fail(state, AgentOutcome.CostLimit, "cost_limit", "The cost budget is used up.")
            null -> null
        }
    }

    private fun modelFailure(state: State, e: ModelException): AgentResult {
        val msg = redactor.redact(e.message.orEmpty())
        val err = AgentError(AgentOutcome.ModelFailed, "model_${e.kind.name.lowercase()}", msg, e.kind, e.status, e.retryable, e.hint, e.attempts)
        return finish(state, AgentOutcome.ModelFailed, state.lastText, null, err)
    }

    private data class ToolRun(val text: String, val status: ToolStatus, val wrap: Boolean)

    private suspend fun runTool(state: State, rawCall: ToolCall, offered: List<AgentTool>): ToolRun {
        val started = System.nanoTime()
        suspend fun record(tool: AgentTool?, status: ToolStatus, detail: String, callId: String = rawCall.id) {
            state.trace += ToolTrace(state.iterations, rawCall.name, tool?.risk, status, redactor.redact(detail).take(200))
            state.emit(AgentEvent.ToolFinished(callId, rawCall.name, status, redactor.redact(detail).take(200), (System.nanoTime() - started) / 1_000_000))
        }
        if (rawCall.argumentsError != null && rawCall.rawArguments == null) {
            record(null, ToolStatus.BadArguments, rawCall.argumentsError)
            return ToolRun(ToolErrors.format("The arguments for ${rawCall.name} were not a valid JSON object (${rawCall.argumentsError}). Send a JSON object that matches the tool schema."), ToolStatus.BadArguments, false)
        }
        val tool = offered.firstOrNull { it.name == rawCall.name }
        if (tool == null) {
            record(null, ToolStatus.Unknown, "unknown tool")
            return ToolRun(ToolErrors.format("There is no tool named \"${rawCall.name.take(64)}\". Available tools: ${offered.joinToString { it.name }.ifEmpty { "none" }}."), ToolStatus.Unknown, false)
        }
        val call = ToolArguments.adapt(tool.parameters, rawCall)
        if (call.argumentsError != null) {
            record(tool, ToolStatus.BadArguments, call.argumentsError)
            return ToolRun(ToolErrors.format("The arguments for ${call.name} were not a valid JSON object (${call.argumentsError}). Send a JSON object that matches the tool schema."), ToolStatus.BadArguments, false)
        }
        val schemaErrors = SchemaCheck.validate(tool.parameters, call.arguments)
        if (schemaErrors.isNotEmpty()) {
            record(tool, ToolStatus.BadArguments, schemaErrors.first())
            return ToolRun(ToolErrors.format("The arguments for ${tool.name} are not valid: ${schemaErrors.joinToString("; ")}. Fix them and try again."), ToolStatus.BadArguments, false)
        }
        state.emit(AgentEvent.ToolRunning(call.id, call.name, tool.risk))
        val decision = decide(state, tool, call)
        if (decision is ApprovalDecision.Denied) {
            record(tool, ToolStatus.Denied, decision.feedback?.let { "${decision.reason}: $it" } ?: decision.reason)
            val text = when {
                !decision.feedback.isNullOrBlank() ->
                    "Not allowed: the person declined ${tool.name}. Their feedback: \"${redactor.redact(decision.feedback)}\". Take it into account and do not repeat the same call unchanged."
                else -> "Not allowed: ${decision.reason}. Do not call ${tool.name} again for this task; continue without it or explain what you could not do."
            }
            return ToolRun(text, ToolStatus.Denied, false)
        }
        val outcome = try {
            withTimeoutOrNull(limits.perToolTimeoutMs) { tool.execute(call.arguments) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolOutcome("the tool failed: ${e.message ?: e::class.java.simpleName}", isError = true)
        }
        if (outcome == null) {
            val t = ToolErrors.format("${tool.name} did not answer within ${limits.perToolTimeoutMs / 1000.0}s.")
            record(tool, ToolStatus.TimedOut, "timed out after ${limits.perToolTimeoutMs} ms")
            return ToolRun(t, ToolStatus.TimedOut, false)
        }
        var text = if (outcome.isError) ToolErrors.format(outcome.text, outcome.httpStatus ?: ToolErrors.statusIn(outcome.text), outcome.errorBody, redactor) else redactor.redact(outcome.text)
        if (text.length > limits.maxToolResultChars) text = text.take(limits.maxToolResultChars) + "…[truncated]"
        val status = if (outcome.isError) ToolStatus.ToolError else ToolStatus.Ok
        record(tool, status, if (outcome.isError) text else "ok, ${text.length} chars")
        return ToolRun(text, status, wrap = true)
    }

    /**
     * Asks the policy. While a person is asked, the working clock stops and the answer has its own time limit
     * ([AgentLimits.approvalTimeoutMs]); running past it counts as a refusal.
     */
    private suspend fun decide(state: State, tool: AgentTool, call: ToolCall): ApprovalDecision {
        var asked = false
        var t0 = 0L
        val d = try {
            withTimeoutOrNull(limits.approvalTimeoutMs) {
                policy.decide(tool, call.arguments) { request ->
                    asked = true
                    t0 = System.nanoTime()
                    state.wall.pause()
                    state.emit(AgentEvent.ApprovalRequested(request))
                }
            } ?: ApprovalDecision.Denied("no answer was given within ${limits.approvalTimeoutMs / 1000}s")
        } finally {
            if (asked) state.wall.resume()
        }
        if (asked) {
            state.emit(AgentEvent.ApprovalResolved(tool.name, d is ApprovalDecision.Approved, (d as? ApprovalDecision.Denied)?.feedback, (System.nanoTime() - t0) / 1_000_000))
        }
        return d
    }

    private suspend fun conclude(state: State, text: String, task: String, memoryKey: String): AgentResult {
        var structured: Any? = null
        var finalText = text
        if (parser != null) {
            val outcome = parser.parseWithRepair(client, text, maxOutputTokens = limits.maxOutputTokens)
            account(state, null, outcome.usage)
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

    private fun finish(state: State, outcome: AgentOutcome, text: String, structured: Any?, error: AgentError?): AgentResult {
        val cost = state.ledger.costEstimate
        return AgentResult(
            outcome = outcome, text = text, structured = structured, usage = state.usage,
            iterations = state.iterations, toolCalls = state.toolCalls, trace = state.trace.toList(),
            estimatedCost = cost.usd, error = error, truncated = state.truncated, fallbackUsed = state.fallbackUsed,
            usageByModel = state.ledger.usageByModel, cost = cost,
        )
    }

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
