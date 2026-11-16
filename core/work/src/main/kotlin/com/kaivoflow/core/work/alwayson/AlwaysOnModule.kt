package com.kaivoflow.core.work.alwayson

import android.content.Context
import com.kaivoflow.core.engine.alwayson.AlertThrottle
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.DwellTracker
import com.kaivoflow.core.engine.alwayson.ListenerTracker
import com.kaivoflow.core.engine.alwayson.ResponseGate
import com.kaivoflow.core.engine.alwayson.InboxStore
import com.kaivoflow.core.engine.alwayson.PayloadFiles
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SparkInbox
import com.kaivoflow.core.engine.alwayson.WakeLockLedger
import com.kaivoflow.core.engine.alwayson.WallClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import java.io.File
import javax.inject.Singleton

/** Registrars contribute with `@Binds @IntoSet`. The empty `@Multibinds` keeps the set injectable while there are none. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlwaysOnBindings {
    @Multibinds abstract fun registrars(): Set<SparkRegistrar>
    @Binds @IntoSet abstract fun geofenceRegistrar(impl: GeofenceRegistrar): SparkRegistrar
    @Binds @IntoSet abstract fun webhookRegistrar(impl: WebhookRegistrar): SparkRegistrar
    @Binds @IntoSet abstract fun notificationRegistrar(impl: NotificationListenerRegistrar): SparkRegistrar
    @Binds @IntoSet abstract fun sourcesRegistrar(impl: SourcesRegistrar): SparkRegistrar
}

@Module
@InstallIn(SingletonComponent::class)
object AlwaysOnModule {
    @Provides @Singleton fun clock(): Clock = WallClock

    @Provides @Singleton fun alertThrottle(clock: Clock): AlertThrottle = AlertThrottle(clock)

    @Provides @Singleton fun wakeLockLedger(clock: Clock): WakeLockLedger = WakeLockLedger(clock)

    @Provides @Singleton
    fun payloadFiles(@ApplicationContext context: Context): PayloadFiles = FilePayloadFiles(File(context.filesDir, "spark_inbox"))

    @Provides @Singleton
    fun sparkInbox(store: InboxStore, files: PayloadFiles, clock: Clock, recorder: ReliabilityRecorder): SparkInbox =
        SparkInbox(store, files, clock, onDuplicate = {
            recorder.record(ReliabilityKinds.INBOX_DUPLICATE, Severity.INFO, it.flowId, it.dedupeKey)
        })

    @Provides @Singleton fun responseGate(): ResponseGate = ResponseGate()

    @Provides @Singleton fun listenerTracker(clock: Clock): ListenerTracker = ListenerTracker(clock)

    @Provides @Singleton
    fun dwellTracker(@ApplicationContext context: Context): DwellTracker = DwellTracker(PrefsDwellStore(context))
}

/** Dwell start times survive a process restart, so a Flow that is mid-dwell when the process dies still fires. */
internal class PrefsDwellStore(context: Context) : DwellTracker.DwellStore {
    private val prefs = context.getSharedPreferences("kaivo.dwell", Context.MODE_PRIVATE)
    override fun load(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    override fun save(entries: Map<String, Long>) {
        val e = prefs.edit().clear()
        entries.forEach { (k, v) -> e.putLong(k, v) }
        e.apply()
    }
}
