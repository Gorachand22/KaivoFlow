package com.kaivoflow.core.model

/**
 * External workspace/space grouping multiple Flows together (e.g. "Personal", "Work", "Home Automation").
 * Holds scoped environment variables and credentials.
 */
data class WorkflowGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val color: String = "amber",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Environment / Project variable scoped to a [WorkflowGroup] (or global if [groupId] is null).
 * Accessible in Step expressions via `{{ $vars.KEY }}`.
 */
data class WorkflowVariable(
    val id: String,
    val groupId: String? = null,
    val key: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
