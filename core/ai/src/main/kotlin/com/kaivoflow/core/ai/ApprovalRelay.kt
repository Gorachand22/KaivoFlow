package com.kaivoflow.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The bridge between the agent loop and a screen that can ask a person (audit 8.4, interactive host).
 *
 * The loop calls [approve]; a screen that shows a card observes [pending] and answers with [respond].
 * One question is open at a time (later ones wait in line). When no screen is attached the request is
 * denied at once instead of waiting for ever, so a Run started from a place without a card can never hang.
 * Cancelling the caller clears the card. Nothing here knows about Android.
 */
class ApprovalRelay : ApprovalCallback {
    private val queue = Mutex()
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    private val _presenters = MutableStateFlow(0)
    private val _inline = MutableStateFlow(0)
    @Volatile private var answer: CompletableDeferred<ApprovalDecision>? = null

    /** The question to show now, or null. */
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    /** How many screens can show a card. Zero means every request is denied. */
    val presenters: StateFlow<Int> = _presenters.asStateFlow()

    /** How many of those show the card inside a chat; the global dialog stays hidden while one does. */
    val inlinePresenters: StateFlow<Int> = _inline.asStateFlow()

    /** Call when a screen starts to show cards; call the returned function when it stops. [inline] is true for a chat screen. */
    fun attach(inline: Boolean = false): () -> Unit {
        _presenters.update { it + 1 }
        if (inline) _inline.update { it + 1 }
        var done = false
        return {
            if (!done) {
                done = true
                _presenters.update { (it - 1).coerceAtLeast(0) }
                if (inline) _inline.update { (it - 1).coerceAtLeast(0) }
                // The last screen leaving takes the open question with it: nobody can answer it any more.
                if (_presenters.value == 0) answer?.complete(ApprovalDecision.Denied("the screen that asked was closed"))
            }
        }
    }

    /** The person's answer to the open question. Ignored when nothing is open. */
    fun respond(approved: Boolean, reason: String = "the person said no") {
        answer?.complete(if (approved) ApprovalDecision.Approved else ApprovalDecision.Denied(reason))
    }

    override suspend fun approve(request: ApprovalRequest): ApprovalDecision {
        if (_presenters.value == 0) return ApprovalDecision.Denied("no screen is open to confirm ${request.toolName}")
        return queue.withLock {
            if (_presenters.value == 0) return@withLock ApprovalDecision.Denied("no screen is open to confirm ${request.toolName}")
            val d = CompletableDeferred<ApprovalDecision>()
            answer = d
            _pending.value = request
            try {
                d.await()
            } catch (e: CancellationException) {
                throw e
            } finally {
                answer = null
                _pending.value = null
            }
        }
    }
}
