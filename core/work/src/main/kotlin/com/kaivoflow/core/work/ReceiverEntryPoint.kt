package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.work.alwayson.AlarmScheduler
import com.kaivoflow.core.work.alwayson.DeviceEvents
import com.kaivoflow.core.work.alwayson.EngineController
import com.kaivoflow.core.work.alwayson.Reconciler
import com.kaivoflow.core.work.alwayson.ReliabilityRecorder
import com.kaivoflow.core.work.alwayson.ScheduleFirer
import com.kaivoflow.core.work.alwayson.SelfTestRunner
import com.kaivoflow.core.work.alwayson.WakeLockManager
import com.kaivoflow.core.work.alwayson.WatchdogScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Dependencies for manifest-declared BroadcastReceivers.
 *
 * Receivers read these through [ReceiverEntryPoints] instead of field injection, because Hilt's
 * generated receiver base class injects inside `onReceive`, which Kotlin cannot call through `super`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun deviceEvents(): DeviceEvents
    fun runNotifications(): RunNotifications
    fun reconciler(): Reconciler
    fun scheduleFirer(): ScheduleFirer
    fun alarmScheduler(): AlarmScheduler
    fun watchdogScheduler(): WatchdogScheduler
    fun engineController(): EngineController
    fun wakeLocks(): WakeLockManager
    fun reliabilityRecorder(): ReliabilityRecorder
    fun selfTest(): SelfTestRunner
}

object ReceiverEntryPoints {
    fun of(context: Context): ReceiverEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
}
