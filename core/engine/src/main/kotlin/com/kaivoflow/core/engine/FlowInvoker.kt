package com.kaivoflow.core.engine

import com.kaivoflow.core.model.FlowData

/**
 * Lets a Step run another Flow on the same phone and bring its items back.
 *
 * The engine stays Android-free: the app wires this to the Room-backed FlowRepository, while
 * JVM tests can supply a tiny fake. [depth] protects against a Sub-Flow accidentally calling
 * itself forever.
 */
interface FlowInvoker {
    suspend fun invoke(flowId: String, items: List<FlowData>, depth: Int): List<FlowData>
}
