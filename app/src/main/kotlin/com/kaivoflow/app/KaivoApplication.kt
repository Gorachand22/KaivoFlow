package com.kaivoflow.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point.
 *
 * WorkManager is initialised on demand (the manifest removes the automatic startup initializer and
 * this class is the [Configuration.Provider] that supplies it), so Runs can be constructed with
 * injected repositories instead of being `new`-ed inside a Worker.
 *
 * Scheduling is re-registered on every cold start: that repairs the queue after an app update, a
 * timezone change, or an OEM force-stop that silently wiped it.
 */
@HiltAndroidApp
class KaivoApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: FlowScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch { runCatching { scheduler.rescheduleAll() } }
    }
}
