package com.kaivoflow.core.work.alwayson

import android.content.Context
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.TriggerRegistrationEntity
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.EngineState
import com.kaivoflow.core.engine.alwayson.EnqueueResult
import com.kaivoflow.core.engine.alwayson.FlowTokens
import com.kaivoflow.core.engine.alwayson.ReachMode
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.ResponseMode
import com.kaivoflow.core.engine.alwayson.ResponsePlan
import com.kaivoflow.core.engine.alwayson.RouteSpec
import com.kaivoflow.core.engine.alwayson.RouteTable
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SinkResult
import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.engine.alwayson.WebhookListenerStatus
import com.kaivoflow.core.engine.alwayson.WebhookReach
import com.kaivoflow.core.engine.alwayson.WebhookSink
import com.kaivoflow.core.engine.alwayson.WebhookStatusMapper
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.work.WebhookServer
import com.kaivoflow.core.work.WebhookTokenStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a webhook request is stored. The Inbox row is committed before this returns, so the server replies only after the event
 * is durable. A failed write is reported as [SinkResult.Failed] (the caller gets a 503 and may retry); a failure to merely wake
 * the consumer afterwards never turns an accepted write into a failure (see `SparkIntake.enqueue`).
 */
@Singleton
class InboxWebhookSink @Inject constructor(private val intake: SparkIntake) : WebhookSink {
    override suspend fun accept(route: RouteSpec, method: String, payload: Map<String, Any?>, idempotencyKey: String?): SinkResult = try {
        val result = intake.enqueue(
            flowId = route.flowId,
            sparkId = route.sparkId,
            kind = KIND,
            items = listOf(FlowData(payload)),
            meta = mapOf(
                "path" to route.key,
                "method" to method,
                "respond" to if (route.response.mode == ResponseMode.WaitForRun) AndroidRunExecutor.RESPOND_WAIT else "none",
            ),
            sourceId = idempotencyKey,
            source = IntakeSource.Event,
        )
        when (result) {
            is EnqueueResult.Stored -> SinkResult.Stored(result.id)
            is EnqueueResult.Duplicate -> SinkResult.Duplicate
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        SinkResult.Failed
    }

    companion object { const val KIND = "webhook" }
}

/**
 * Keeps the listener and the route table true to the published Flows (spec 3.7 and 3.2, AO-11). It is a Reconciler registrar, so
 * the table is rebuilt on publish, pause, unpublish, boot, update and every Pulse, and the service calls [apply] when it starts.
 *
 * The table is always rebuilt from the repository, never from the Flows a caller passes: `Reconciler.syncFlow` hands over only the
 * Flow that changed, and a partial table would silently drop every other route.
 *
 * Honest status: each webhook Spark gets a `trigger_registration` row (Armed, Pending while the Engine is not running, or Failed
 * with `PortInUse`, `InvalidConfig`, `EngineStartBlocked`...), and [WebhookServer.status] carries the listener state for the UI.
 */
@Singleton
class WebhookRegistrar @Inject constructor(
    private val flows: FlowRepository,
    private val liveFlowSource: LiveFlows,
    private val lifecycle: FlowLifecycleRepository,
    private val server: WebhookServer,
    private val registrations: TriggerRegistrationRepository,
    private val settings: EngineSettings,
    private val runtime: EngineRuntime,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) : SparkRegistrar {
    private val mutex = Mutex()
    @Volatile private var lastPlanKey: String? = null

    override suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger) { apply() }

    suspend fun apply(): WebhookListenerStatus = mutex.withLock {
        val liveFlows = liveFlowSource.live()
        val table = RouteTable.fromFlows(liveFlows)
        server.updateRoutes(table)

        val engineRunning = runtime.serviceAlive
        val plan = WebhookReach.plan(settings.webhookLanEnabled, settings.webhookPort)
        val before = server.status.value
        val status = if (engineRunning && table.size > 0) server.bind(plan) else { server.stop(); WebhookListenerStatus.Stopped }

        val planKey = "${plan.mode}:${plan.port}"
        if (planKey != lastPlanKey && status is WebhookListenerStatus.Listening) {
            if (lastPlanKey != null) recorder.record(ReliabilityKinds.WEBHOOK_REACH_CHANGED, Severity.INFO, detail = planKey)
            lastPlanKey = planKey
        }
        if (status != before && (status is WebhookListenerStatus.PortInUse || status is WebhookListenerStatus.Failed || status is WebhookListenerStatus.InvalidPort)) {
            recorder.record(ReliabilityKinds.WEBHOOK_BIND_FAILED, Severity.WARN, detail = status.toString())
        }
        writeRegistrations(table, status, engineRunning)
        status
    }

    private suspend fun writeRegistrations(table: RouteTable, status: WebhookListenerStatus, engineRunning: Boolean) {
        val now = clock.now()
        val existing = registrations.all().filter { it.kind == SparkTypes.WEBHOOK }.associateBy { it.flowId to it.sparkId }
        val wanted = HashSet<Pair<String, String>>()

        suspend fun write(spec: RouteSpec) {
            val key = spec.flowId to spec.sparkId
            wanted += key
            var state = WebhookStatusMapper.registration(spec, table, status, engineRunning)
            if (state.kind == SparkRegState.Kind.Pending && runtime.state.value == EngineState.StartBlocked) {
                state = SparkRegState.failed(FailureCode.EngineStartBlocked, "EngineStartBlocked")
            }
            val old = existing[key]
            val row = toRow(spec, state, old, now)
            if (old == null || old.state != row.state || old.detail != row.detail) registrations.upsert(row)
        }

        for (c in table.conflicts) write(RouteSpec(c.loserFlowId, c.loserSparkId, c.key, "", null, ResponsePlan.IMMEDIATE))
        for (spec in table.keys.mapNotNull { table.lookup(it) }) write(spec)
        existing.keys.filter { it !in wanted }.forEach { (flowId, sparkId) -> registrations.remove(flowId, sparkId) }
    }

    private fun toRow(spec: RouteSpec, s: SparkRegState, old: TriggerRegistrationEntity?, now: Long): TriggerRegistrationEntity {
        val state = when (s.kind) {
            SparkRegState.Kind.Armed -> com.kaivoflow.core.data.RegistrationState.ARMED
            SparkRegState.Kind.Pending -> com.kaivoflow.core.data.RegistrationState.PENDING
            SparkRegState.Kind.FailedTransient -> com.kaivoflow.core.data.RegistrationState.FAILED_TRANSIENT
            SparkRegState.Kind.FailedPermanent -> com.kaivoflow.core.data.RegistrationState.FAILED_PERMANENT
        }
        val sameState = old?.state == state
        return TriggerRegistrationEntity(
            flowId = spec.flowId, sparkId = spec.sparkId, kind = SparkTypes.WEBHOOK, state = state, detail = s.detail,
            since = if (sameState) old!!.since else now,
            retryCount = if (s.isFailure) (old?.retryCount ?: 0) + 1 else 0,
            nextRetryAt = null,
        )
    }
}

/**
 * What the settings screen and the Reliability Center need from the webhook listener: the visible status, the reach switch, and the
 * tokens to copy. Changing a setting reconciles routes at once, so the listener rebinds without waiting for a Pulse.
 */
@Singleton
class WebhookAdmin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: EngineSettings,
    private val tokens: WebhookTokenStore,
    private val server: WebhookServer,
    private val reconciler: Lazy<Reconciler>,
) {
    val status: StateFlow<WebhookListenerStatus> get() = server.status

    val reachMode: ReachMode get() = if (settings.webhookLanEnabled) ReachMode.Lan else ReachMode.Loopback
    val port: Int get() = settings.webhookPort

    suspend fun setLanEnabled(enabled: Boolean) {
        if (settings.webhookLanEnabled == enabled) return
        settings.webhookLanEnabled = enabled
        reconciler.get().reconcile(ReconcileTrigger.RoutesChanged)
    }

    /** False (and nothing changes) for a port outside 1024..65535. */
    suspend fun setPort(port: Int): Boolean {
        if (!WebhookReach.validPort(port)) return false
        if (settings.webhookPort != port) {
            settings.webhookPort = port
            reconciler.get().reconcile(ReconcileTrigger.RoutesChanged)
        }
        return true
    }

    /** The token for local callers (loopback, adb forward). It is never accepted over the LAN. */
    fun appToken(): String = tokens.token()

    /** The token to give to one caller. The only credential accepted in LAN mode, and it opens that Flow's webhooks only. */
    fun flowToken(flowId: String): String = FlowTokens.derive(tokens.token(), flowId)

    /** Revokes every Flow token and the app token. */
    suspend fun regenerateTokens() { tokens.regenerate() }

    /** IPv4 addresses a LAN caller would use, to show next to the switch ("http://192.168.1.20:8787/hook/..."). Plain HTTP. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
