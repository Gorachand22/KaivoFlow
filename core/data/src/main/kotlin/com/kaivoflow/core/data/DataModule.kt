package com.kaivoflow.core.data

import android.content.Context
import androidx.room.Room
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.OAuth2TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KaivoDatabase =
        Room.databaseBuilder(context, KaivoDatabase::class.java, KaivoDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun flowDao(db: KaivoDatabase): FlowDao = db.flowDao()
    @Provides fun runDao(db: KaivoDatabase): RunDao = db.runDao()
    @Provides fun credentialDao(db: KaivoDatabase): CredentialDao = db.credentialDao()
    @Provides fun workflowGroupDao(db: KaivoDatabase): WorkflowGroupDao = db.workflowGroupDao()
    @Provides fun workflowVariableDao(db: KaivoDatabase): WorkflowVariableDao = db.workflowVariableDao()
    @Provides fun dataTableDao(db: KaivoDatabase): DataTableDao = db.dataTableDao()
    @Provides fun flowLifecycleDao(db: KaivoDatabase): FlowLifecycleDao = db.flowLifecycleDao()
    @Provides fun triggerRegistrationDao(db: KaivoDatabase): TriggerRegistrationDao = db.triggerRegistrationDao()
    @Provides fun sparkInboxDao(db: KaivoDatabase): SparkInboxDao = db.sparkInboxDao()
    @Provides fun scheduleDao(db: KaivoDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun engineTelemetryDao(db: KaivoDatabase): EngineTelemetryDao = db.engineTelemetryDao()
    @Provides fun stepDataDao(db: KaivoDatabase): StepDataDao = db.stepDataDao()
    @Provides fun stepPinDao(db: KaivoDatabase): StepPinDao = db.stepPinDao()

    @Provides
    @Singleton
    fun provideCryptoBox(): CryptoBox = CryptoBox()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // Repository constructors use a default IO dispatcher; construct them explicitly so Hilt
    // does not need to inject an unqualified CoroutineDispatcher.
    @Provides @Singleton
    fun flowRepository(dao: FlowDao, @ApplicationContext context: Context): FlowRepository =
        RoomFlowRepository(dao, storageDir = context.filesDir)

    @Provides @Singleton
    fun runRepository(dao: RunDao, stepData: StepDataDao): RunRepository = RoomRunRepository(dao, stepData)

    @Provides @Singleton
    fun stepDataStore(dao: StepDataDao): com.kaivoflow.core.engine.stepdata.StepDataStore = StepDataStoreImpl(RoomStepDataAccess(dao))

    @Provides @Singleton
    fun pinStore(
        dao: StepPinDao,
        store: com.kaivoflow.core.engine.stepdata.StepDataStore,
    ): com.kaivoflow.core.engine.stepdata.PinStore =
        com.kaivoflow.core.engine.stepdata.PinService(RoomPinPersistence(dao), store)

    @Provides @Singleton
    fun workflowGroupRepository(dao: WorkflowGroupDao): WorkflowGroupRepository = RoomWorkflowGroupRepository(dao)

    @Provides @Singleton
    fun workflowVariableRepository(dao: WorkflowVariableDao): WorkflowVariableRepository = RoomWorkflowVariableRepository(dao)

    @Provides @Singleton
    fun dataTableRepository(dao: DataTableDao): DataTableRepository = RoomDataTableRepository(dao)

    @Provides @Singleton
    fun flowLifecycleRepository(dao: FlowLifecycleDao): FlowLifecycleRepository = RoomFlowLifecycleRepository(dao)

    @Provides @Singleton
    fun triggerRegistrationRepository(dao: TriggerRegistrationDao): TriggerRegistrationRepository =
        RoomTriggerRegistrationRepository(dao)

    @Provides @Singleton
    fun sparkInboxRepository(dao: SparkInboxDao): SparkInboxRepository = RoomSparkInboxRepository(dao)

    @Provides @Singleton
    fun inboxStore(dao: SparkInboxDao): com.kaivoflow.core.engine.alwayson.InboxStore = RoomInboxStore(dao)

    @Provides @Singleton
    fun scheduleRepository(dao: ScheduleDao): ScheduleRepository = RoomScheduleRepository(dao)

    @Provides @Singleton
    fun engineTelemetryRepository(dao: EngineTelemetryDao): EngineTelemetryRepository = RoomEngineTelemetryRepository(dao)

    @Provides @Singleton
    fun oauth2TokenManager(http: HttpGateway, dao: CredentialDao, cryptoBox: CryptoBox): OAuth2TokenManager =
        OAuth2TokenManager(http, RoomOAuth2TokenStore(dao, cryptoBox))

    @Provides @Singleton
    fun credentialRepository(
        dao: CredentialDao,
        cryptoBox: CryptoBox,
        tokens: OAuth2TokenManager,
    ): CredentialRepository = EncryptedCredentialRepository(dao, cryptoBox, tokenManager = tokens)
}
