package com.kaivoflow.core.work

import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.work.alwayson.Reconciler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes Flow changes for the process lifetime and asks the Reconciler to bring every registration in line (webhook routes,
 * geofences, the notification listener, the Engine's sources). It registers no receivers itself: battery, Wi-Fi, Bluetooth and
 * headset sources moved into the Engine (`EngineSources`), webhook hosting into `WebhookRegistrar`. [start] is called once from
 * `KaivoApplication.onCreate`.
 *
 * `RoutesChanged` is deliberately not a foreground-service start reason (spec 3.5): this runs at every process start, including
 * ones caused by a broadcast, so it must never try to start the Engine.
 */
@Singleton
class SparkRuntime @Inject constructor(
    private val flowRepository: FlowRepository,
    private val reconciler: Reconciler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            flowRepository.observeSummaries().debounce(500).collect {
                runCatching { reconciler.reconcile(ReconcileTrigger.RoutesChanged) }
            }
        }
    }
}
