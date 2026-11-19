package com.kaivoflow.core.ai

import kotlinx.coroutines.CancellationException

/** How risky a tool call is. The class decides who may approve it and whether it is offered at all. */
enum class ToolRisk {
    /** Reads data the Flow already may read, no side effect. Runs without asking. */
    Read,

    /** Edits an in-memory draft; nothing is persisted. Interactive hosts only. */
    WriteDraft,

    /** Saves something durable. Interactive hosts only, after an explicit Apply. */
    WritePersist,

    /** Leaves the device or exposes private data: SMS, messages, HTTP writes, notifications, private reads. */
    ExternalEffect,

    /** Deletes or overwrites data. Never automatic; never offered when nobody is there to confirm. */
    Destructive,
}

enum class ApprovalHost {
    /** A person is looking: chat screen or an approval notification. */
    Interactive,

    /** A Flow Step or the always-on engine: nobody to ask. */
    Unattended,
}

data class ApprovalRequest(
    val toolName: String,
    val risk: ToolRisk,
    val description: String,
    /** Where the effect lands, for example a phone number or host. Already redacted. */
    val target: String,
    /** Arguments, redacted for display. */
    val arguments: Map<String, Any?>,
    /** True when the UI must ask the person to type the tool or object name to confirm. */
    val requiresTypedConfirmation: Boolean,
)

sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data class Denied(val reason: String) : ApprovalDecision
}

/** Implemented by the chat sheet and by the notification approval path alike. */
fun interface ApprovalCallback {
    suspend fun approve(request: ApprovalRequest): ApprovalDecision
}

/**
 * The permission model (audit 8.4). One instance per Run: it counts unattended calls for the rate limit.
 *
 * Interactive: Read automatic; WriteDraft automatic; WritePersist, ExternalEffect and Destructive need
 * the callback and are denied when there is none. Unattended: Read automatic; ExternalEffect only for
 * tools named in [unattendedAllow] and at most [maxUnattendedCallsPerTool] times; everything else is
 * not offered and is denied if the model asks anyway. Destructive is never automatic.
 */
class ApprovalPolicy(
    val host: ApprovalHost,
    private val callback: ApprovalCallback? = null,
    private val unattendedAllow: Set<String> = emptySet(),
    private val maxUnattendedCallsPerTool: Int = 5,
    private val redactor: Redactor = Redactor(),
) {
    private val unattendedCalls = HashMap<String, Int>()

    /** Whether the model is told about this tool at all. */
    fun isOffered(tool: AgentTool): Boolean = when (tool.risk) {
        ToolRisk.Read, ToolRisk.ExternalEffect -> true
        ToolRisk.WriteDraft, ToolRisk.WritePersist, ToolRisk.Destructive -> host == ApprovalHost.Interactive
    }

    suspend fun decide(tool: AgentTool, arguments: Map<String, Any?>): ApprovalDecision {
        if (!isOffered(tool)) return ApprovalDecision.Denied("${tool.name} is not available here")
        if (tool.risk == ToolRisk.Read || (tool.risk == ToolRisk.WriteDraft && host == ApprovalHost.Interactive)) return ApprovalDecision.Approved
        if (host == ApprovalHost.Unattended) {
            if (tool.risk != ToolRisk.ExternalEffect) return ApprovalDecision.Denied("${tool.name} needs a person to confirm")
            if (tool.name !in unattendedAllow) return ApprovalDecision.Denied("${tool.name} is not allowed to run without asking")
            val n = (unattendedCalls[tool.name] ?: 0) + 1
            if (n > maxUnattendedCallsPerTool) return ApprovalDecision.Denied("${tool.name} reached its limit of $maxUnattendedCallsPerTool calls for this run")
            unattendedCalls[tool.name] = n
            return ApprovalDecision.Approved
        }
        val cb = callback ?: return ApprovalDecision.Denied("no one is available to confirm ${tool.name}")
        val request = ApprovalRequest(
            toolName = tool.name,
            risk = tool.risk,
            description = redactor.redact(tool.description),
            target = redactor.redact(tool.describeTarget(arguments)),
            arguments = (redactor.redactValue(arguments) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
            requiresTypedConfirmation = tool.risk == ToolRisk.Destructive,
        )
        return try {
            cb.approve(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ApprovalDecision.Denied("the confirmation could not be completed")
        }
    }
}
