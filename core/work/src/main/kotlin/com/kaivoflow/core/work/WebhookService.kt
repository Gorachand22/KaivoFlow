package com.kaivoflow.core.work

import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.work.alwayson.ReliabilityRecorder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the [WebhookServer] socket in step with the published Flows. The separate `WebhookService` (a `dataSync` foreground
 * service with a 6 hour cap on targetSdk 35+) is gone: the socket now lives inside the Always-On Engine, which is a `specialUse`
 * service (spec 3.4). Reach modes and registration failures per Spark are AO-11; here a bind failure becomes a reliability event.
 */
@Singleton
class WebhookController @Inject constructor(
    private val flowRepository: FlowRepository,
    private val server: WebhookServer,
    private val engine: com.kaivoflow.core.work.alwayson.EngineController,
    private val recorder: ReliabilityRecorder,
) {
    /** True when any published Flow has an enabled webhook Spark. */
    suspend fun needed(): Boolean = flowRepository.all().any { flow ->
        flow.isActive && flow.triggers.any { it.type == TriggerSpecs.WEBHOOK && !it.disabled }
    }

    /**
     * Called by `SparkRuntime` when Flows change. The Engine starts or stops through `EngineController` (a webhook Spark makes it
     * Required); the listener itself is applied by the service via [sync] once it runs.
     */
    suspend fun sync() {
        engine.reassess(com.kaivoflow.core.engine.alwayson.StartReason.Reassess)
    }

    /** Applies the listener state inside the running Engine: refresh routes and bind, or release the port. */
    suspend fun sync(engineNeeded: Boolean) {
        if (engineNeeded && needed()) {
            server.refreshRoutes()
            if (!server.isRunning) {
                try {
                    server.start()
                } catch (e: Exception) {
                    recorder.record(ReliabilityKinds.RECONCILE_FAILED, Severity.WARN, detail = "webhook bind: ${e.javaClass.simpleName}")
                }
            }
        } else {
            server.stop()
        }
    }

    fun stopServer() = server.stop()
}
