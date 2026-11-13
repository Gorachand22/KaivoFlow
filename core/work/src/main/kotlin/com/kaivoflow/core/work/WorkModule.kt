package com.kaivoflow.core.work

import android.content.Context
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

/**
 * Wires Hilt's worker factory into WorkManager. The application class calls this once from
 * `onCreate` (see `KaivoApplication`), which is the only supported way to get injected Workers.
 */
@Singleton
class WorkManagerInitializer @Inject constructor(
    private val workerFactory: HiltWorkerFactory,
) {
    fun configure(context: Context) {
        if (WorkManager.isInitialized()) return
        WorkManager.initialize(
            context,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build(),
        )
    }
}
