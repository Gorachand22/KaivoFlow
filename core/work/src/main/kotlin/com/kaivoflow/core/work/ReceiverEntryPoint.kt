package com.kaivoflow.core.work

import android.content.Context
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
    fun scheduler(): FlowScheduler
    fun runNotifications(): RunNotifications
}

object ReceiverEntryPoints {
    fun of(context: Context): ReceiverEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
}
