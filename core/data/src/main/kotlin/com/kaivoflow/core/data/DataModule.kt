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
import kotlinx.coroutines.sync.Mutex

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
    @Provides fun vaultMetaDao(db: KaivoDatabase): VaultMetaDao = db.vaultMetaDao()
    @Provides fun agentMemoryDao(db: KaivoDatabase): AgentMemoryDao = db.agentMemoryDao()
    @Provides fun aiUsageDao(db: KaivoDatabase): AiUsageDao = db.aiUsageDao()
    @Provides fun aiEventDao(db: KaivoDatabase): AiEventDao = db.aiEventDao()

    @Provides
    @Singleton
    fun provideCryptoBox(): CryptoBox = CryptoBox()

    @Provides
    @Singleton
    fun secretCipher(box: CryptoBox): SecretCipher = box
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
    fun runRepository(dao: RunDao, stepData: StepDataDao, secrets: SecretValueSource): RunRepository =
        RedactingRunRepository(RoomRunRepository(dao, stepData), secrets)

    @Provides @Singleton
    fun stepDataStore(dao: StepDataDao, secrets: SecretValueSource): com.kaivoflow.core.engine.stepdata.StepDataStore =
        RedactingStepDataStore(StepDataStoreImpl(RoomStepDataAccess(dao)), secrets)

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

    /** One lock for every writer of the credentials table (user Save, OAuth refresh, one-time migrations). */
    @Provides @Singleton
    @VaultWriteLock
    fun vaultWriteLock(): Mutex = Mutex()

    @Provides @Singleton
    fun secretValues(dao: CredentialDao, cipher: SecretCipher): SecretValueSource = VaultSecretValues(dao, cipher)

    @Provides @Singleton
    fun oauth2TokenManager(
        http: HttpGateway, dao: CredentialDao, cipher: SecretCipher, secrets: SecretValueSource, @VaultWriteLock lock: Mutex,
    ): OAuth2TokenManager = OAuth2TokenManager(http, RoomOAuth2TokenStore(dao, cipher, secrets = secrets, writeLock = lock))

    @Provides @Singleton
    fun credentialRepository(
        dao: CredentialDao,
        cipher: SecretCipher,
        tokens: OAuth2TokenManager,
        secrets: SecretValueSource,
        meta: VaultMetaDao,
        @VaultWriteLock lock: Mutex,
        localHosts: com.kaivoflow.core.model.LocalHostRegistrar,
    ): CredentialRepository = EncryptedCredentialRepository(
        dao, cipher, tokenManager = tokens,
        migrations = CredentialMigrations(dao, cipher, VaultMetaStore(meta), lock),
        secrets = secrets, writeLock = lock, localHosts = localHosts,
    )

    @Provides @Singleton
    fun aiEventLog(dao: AiEventDao, secrets: SecretValueSource): AiEventLog = RoomAiEventLog(dao, secrets)

    @Provides @Singleton
    fun usageLedger(dao: AiUsageDao): UsageLedger = RoomUsageLedger(dao)

    @Provides @Singleton
    fun aiUsageSink(ledger: UsageLedger): AiUsageSink = ledger

    @Provides @Singleton
    fun agentMemories(dao: AgentMemoryDao, secrets: SecretValueSource): AgentMemoryFactory = AgentMemoryFactory(dao, secrets)
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class VaultWriteLock
