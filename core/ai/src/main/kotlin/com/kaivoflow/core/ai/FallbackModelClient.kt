package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.KaivoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A chain of models: the first answers; when it fails (after its own retries) the same request goes to the next.
 * Like n8n's fallback socket, any failure moves on, but cancellation never does: it ends the call at once and no
 * later model is tried. The answer says which model served it ([ChatResponse.servedBy], 0 is the first), and a
 * stream reports the switch with [StreamEvent.FallbackUsed]. When every model fails, the first model's error is
 * thrown with the last error appended to its message.
 */
class FallbackModelClient(private val models: List<ChatModelClient>) : ChatModelClient {
    init { require(models.isNotEmpty()) { "a model chain needs at least one model" } }

    private fun combine(first: ModelException?, last: Throwable): ModelException {
        val lastMsg = (last.message ?: last::class.java.simpleName).take(200)
        if (first == null) return ModelException(ModelErrorKind.Server, "The model call failed: $lastMsg")
        if (models.size == 1 || first === last) return first
        return ModelException(first.kind, "${first.message} (the fallback model also failed: $lastMsg)", first.status, first.retryAfterMs, first.providerCode, first.attempts)
    }

    override suspend fun complete(request: ChatRequest): ChatResponse {
        var first: ModelException? = null
        var last: Throwable? = null
        for ((i, m) in models.withIndex()) {
            try {
                return m.complete(request).copy(servedBy = i)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (first == null) first = e as? ModelException ?: ModelException(ModelErrorKind.Server, "The model call failed: ${(e.message ?: e::class.java.simpleName).take(200)}")
            }
        }
        throw combine(first, last!!)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        var first: ModelException? = null
        var last: Throwable? = null
        for ((i, m) in models.withIndex()) {
            var emitted = false
            try {
                m.stream(request).collect { ev ->
                    if (ev is StreamEvent.TextDelta || ev is StreamEvent.ThinkingDelta || ev is StreamEvent.ToolCallStart) emitted = true
                    emit(if (ev is StreamEvent.Done) StreamEvent.Done(ev.response.copy(servedBy = i)) else ev)
                }
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                last = e; if (first == null) first = e
            } catch (e: KaivoException) {
                last = e; if (first == null) first = ModelException(ModelErrorKind.Network, e.message.orEmpty())
            }
            if (i + 1 < models.size) emit(StreamEvent.FallbackUsed(i, i + 1, (last.message ?: "").take(160), emitted))
        }
        throw combine(first, last!!)
    }
}
