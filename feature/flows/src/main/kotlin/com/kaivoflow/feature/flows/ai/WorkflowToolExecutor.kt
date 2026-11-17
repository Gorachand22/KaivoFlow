package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.util.UUID

/**
 * Sovereign implementation of n8n's ai-workflow-builder tools:
 * - add-node.tool.ts
 * - connect-nodes.tool.ts
 * - remove-node.tool.ts
 * - update-node-parameters.tool.ts
 * - validate-structure.tool.ts
 * - get-workflow-overview.tool.ts
 */
class WorkflowToolExecutor {

    /**
     * Tool: add_node (adds a step to the workflow)
     */
    fun addNode(
        flow: Flow,
        type: String,
        name: String,
        params: Map<String, Any?> = emptyMap(),
    ): Pair<Flow, ToolExecution> {
        val stepId = "step_${UUID.randomUUID().toString().take(6)}"
        val lastY = flow.steps.maxOfOrNull { it.y } ?: 100f
        val newStep = StepNode(
            id = stepId,
            type = type,
            name = name,
            params = params.toImmutableMap(),
            x = 100f,
            y = lastY + 160f,
        )

        val updatedFlow = flow.copy(
            steps = (flow.steps + newStep).toImmutableList(),
            updatedAt = System.currentTimeMillis(),
        )

        val record = ToolExecution(
            toolName = "add_node",
            description = "Added step \"$name\" ($type)",
            isSuccess = true,
        )

        return updatedFlow to record
    }

    /**
     * Tool: connect_nodes (connects fromStepId -> toStepId)
     */
    fun connectNodes(
        flow: Flow,
        fromStepId: String,
        toStepId: String,
    ): Pair<Flow, ToolExecution> {
        val streamId = "stream_${UUID.randomUUID().toString().take(6)}"
        val fromStep = flow.steps.find { it.id == fromStepId }
        val toStep = flow.steps.find { it.id == toStepId }

        val newStream = StreamEdge(
            id = streamId,
            fromStepId = fromStepId,
            toStepId = toStepId,
        )

        val updatedFlow = flow.copy(
            streams = (flow.streams + newStream).toImmutableList(),
            updatedAt = System.currentTimeMillis(),
        )

        val fromLabel = fromStep?.name ?: fromStepId
        val toLabel = toStep?.name ?: toStepId
        val record = ToolExecution(
            toolName = "connect_nodes",
            description = "Connected \"$fromLabel\" → \"$toLabel\"",
            isSuccess = true,
        )

        return updatedFlow to record
    }

    /**
     * Tool: update_node_parameters (modifies parameters of a node)
     */
    fun updateNodeParameters(
        flow: Flow,
        stepId: String,
        newParams: Map<String, Any?>,
    ): Pair<Flow, ToolExecution> {
        val targetStep = flow.steps.find { it.id == stepId }
        if (targetStep == null) {
            return flow to ToolExecution(
                toolName = "update_node_parameters",
                description = "Step not found: $stepId",
                isSuccess = false,
            )
        }

        val mergedParams = (targetStep.params + newParams).toImmutableMap()
        val updatedStep = targetStep.copy(params = mergedParams)

        val updatedSteps = flow.steps.map { if (it.id == stepId) updatedStep else it }
        val updatedFlow = flow.copy(
            steps = updatedSteps.toImmutableList(),
            updatedAt = System.currentTimeMillis(),
        )

        val record = ToolExecution(
            toolName = "update_node_parameters",
            description = "Updated parameters on \"${targetStep.name}\"",
            isSuccess = true,
        )

        return updatedFlow to record
    }

    /**
     * Tool: remove_node (removes step and any attached streams)
     */
    fun removeNode(
        flow: Flow,
        stepId: String,
    ): Pair<Flow, ToolExecution> {
        val target = flow.steps.find { it.id == stepId }
        val remainingSteps = flow.steps.filterNot { it.id == stepId }
        val remainingStreams = flow.streams.filterNot { it.fromStepId == stepId || it.toStepId == stepId }

        val updatedFlow = flow.copy(
            steps = remainingSteps.toImmutableList(),
            streams = remainingStreams.toImmutableList(),
            updatedAt = System.currentTimeMillis(),
        )

        val record = ToolExecution(
            toolName = "remove_node",
            description = "Removed step \"${target?.name ?: stepId}\"",
            isSuccess = true,
        )

        return updatedFlow to record
    }

    /**
     * Tool: validate_structure (checks workflow structure and node connectivity)
     */
    fun validateStructure(flow: Flow): Pair<Flow, ToolExecution> {
        val issues = mutableListOf<String>()

        val triggers = flow.steps.filter { it.isTrigger }
        if (triggers.isEmpty()) {
            issues.add("No trigger spark defined")
        }

        flow.steps.filterNot { it.isTrigger }.forEach { nonTrigger ->
            val hasIncoming = flow.streams.any { it.toStepId == nonTrigger.id }
            if (!hasIncoming && flow.steps.size > 1) {
                issues.add("\"${nonTrigger.name}\" has no input connection")
            }
        }

        val desc = if (issues.isEmpty()) {
            "Workflow structure validated: ${flow.steps.size} steps connected cleanly"
        } else {
            "Validation found ${issues.size} advisory: ${issues.joinToString(", ")}"
        }

        val record = ToolExecution(
            toolName = "validate_structure",
            description = desc,
            isSuccess = issues.isEmpty(),
        )

        return flow to record
    }
}
