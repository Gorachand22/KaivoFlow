package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaNode
import kotlinx.coroutines.flow.Flow

/** Which side of a Step a port belongs to. [db] is the stable spelling used in storage. */
enum class Direction(val db: String) {
    In("in"),
    Out("out");

    companion object {
        fun fromDb(s: String): Direction = if (s == "in") In else Out
    }
}

/** Restricts a page to items containing [query] (case-insensitive, anywhere in the item) and/or error items. */
data class ItemFilter(val query: String? = null, val onlyErrors: Boolean = false) {
    val isEmpty: Boolean get() = query.isNullOrEmpty() && !onlyErrors
}

/**
 * One page of stored items. [offset] is the position inside the (filtered) list, [totalStored] the
 * number of stored items on the port, [totalMatching] how many match the filter (equals
 * [totalStored] without one), [totalTrue] the true item count of the execution. [originalIndices]
 * gives each item's position in the full, untruncated list.
 */
data class ItemPage(
    val items: List<Item>,
    val originalIndices: List<Int>,
    val offset: Int,
    val totalStored: Int,
    val totalMatching: Int,
    val totalTrue: Int,
    val truncation: Truncation?,
) {
    companion object {
        val Empty = ItemPage(emptyList(), emptyList(), 0, 0, 0, 0, null)
    }
}

/** Positions (in the stored list) of items that matched. [totalMatches] may exceed `matchingIndices.size`. */
class SearchResult(val matchingIndices: IntArray, val totalMatches: Int) {
    override fun equals(other: Any?): Boolean =
        other is SearchResult && totalMatches == other.totalMatches && matchingIndices.contentEquals(other.matchingIndices)

    override fun hashCode(): Int = 31 * totalMatches + matchingIndices.contentHashCode()
    override fun toString(): String = "SearchResult(${matchingIndices.toList()}, total=$totalMatches)"
}

/** A stored execution as the panels list it: the record with the per-port snapshots (counts, schema, truncation). */
typealias StepExecutionSummary = StepExecutionRecord

/**
 * Storage of captured Step executions (spec 5.4). Items are read page by page; nothing here ever
 * loads a whole port unless the port is small. Implemented by Room in the app and in memory in tests.
 */
interface StepDataStore : StepExecutionSink {
    override suspend fun write(execution: CapturedExecution)

    fun observeExecutions(flowId: String, stepId: String, limit: Int = 20): Flow<List<StepExecutionSummary>>
    suspend fun summary(key: StepExecutionKey): StepExecutionSummary?
    suspend fun latestKey(flowId: String, stepId: String): StepExecutionKey?

    suspend fun page(
        key: StepExecutionKey,
        direction: Direction,
        port: Int,
        offset: Int,
        limit: Int,
        filter: ItemFilter? = null,
    ): ItemPage

    suspend fun schema(key: StepExecutionKey, direction: Direction, port: Int): SchemaNode
    suspend fun search(key: StepExecutionKey, direction: Direction, port: Int, query: String, limit: Int): SearchResult

    /** Deletes every execution of the Run (and with them all port data). */
    suspend fun deleteRun(runId: String)

    /** Size of every stored execution, for the global budget. */
    suspend fun usage(): List<StoredExecutionInfo>

    /** One entry per Run that has stored data, for count-based retention. */
    suspend fun runMetas(): List<RunDataMeta>
}

/** The storage key of the data a finished Step just stored (the Run id, Step id and execution number the event already carries). */
fun com.kaivoflow.core.model.RunEvent.StepFinished.executionKey(): StepExecutionKey = StepExecutionKey(runId, stepId, runIndex)
