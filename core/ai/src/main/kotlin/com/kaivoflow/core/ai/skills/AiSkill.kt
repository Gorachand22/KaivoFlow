package com.kaivoflow.core.ai.skills

/**
 * A specialized skill that an agent can discover, load, and use to solve complex automation tasks.
 */
data class AiSkill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val isBuiltIn: Boolean = true,
    val isEnabled: Boolean = true,
)

object BuiltInSkills {
    val WorkflowBuilder = AiSkill(
        id = "workflow-builder",
        name = "Workflow Builder",
        description = "Expert rules for visual workflow topology, node connections, trigger configurations, and acyclic graphs.",
        instructions = """
            You are an expert in visual workflow graphs.
            - Workflows start with exactly one event trigger (e.g. Webhook, Schedule, Battery Level).
            - Triggers output through port 0.
            - Connect steps sequentially without introducing circular cycles.
            - Ensure all action steps have an incoming connection from a preceding step.
        """.trimIndent(),
        isBuiltIn = true,
        isEnabled = true,
    )

    val DeviceAutomation = AiSkill(
        id = "device-automation",
        name = "Device Automation",
        description = "Expertise in Android hardware triggers (low battery, storage limit, connectivity) and device actions (notifications, toasts, wakeups).",
        instructions = """
            You are an expert in sovereign Android on-device automation.
            - Low battery trigger: configure threshold (e.g. 15% or 20%) and plugged state.
            - Storage full trigger: monitor free storage thresholds.
            - Device actions: Show Notification, Display Toast, Vibrate, Send Webhook.
            - Always prefer battery-friendly event-driven triggers over continuous polling.
        """.trimIndent(),
        isBuiltIn = true,
        isEnabled = true,
    )

    val FlowDebugger = AiSkill(
        id = "flow-debugger",
        name = "Flow Debugger",
        description = "Analyzes execution failures, missing parameter bindings, isolated test run traces, and syntax issues.",
        instructions = """
            You are an expert in workflow diagnostics.
            - Inspect step input/output payloads and parameter bindings.
            - Verify JSON expressions and required headers.
            - Identify unconnected downstream nodes or invalid trigger ports.
        """.trimIndent(),
        isBuiltIn = true,
        isEnabled = true,
    )

    val RestApiIntegrator = AiSkill(
        id = "rest-api-integrator",
        name = "REST API Integrator",
        description = "Handles Webhooks, HTTP Requests, JSON transformation, Bearer Token and Header authentication.",
        instructions = """
            You are an expert in API integration.
            - Configure HTTP methods (GET, POST, PUT, DELETE).
            - Map incoming JSON payloads to step parameters using expressions.
            - Ensure secure handling of API credentials and Authorization headers.
        """.trimIndent(),
        isBuiltIn = true,
        isEnabled = true,
    )

    val defaultSkills: List<AiSkill> = listOf(
        WorkflowBuilder,
        DeviceAutomation,
        FlowDebugger,
        RestApiIntegrator,
    )
}
