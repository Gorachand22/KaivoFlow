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
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun flowDao(db: KaivoDatabase): FlowDao = db.flowDao()
    @Provides fun runDao(db: KaivoDatabase): RunDao = db.runDao()
    @Provides fun credentialDao(db: KaivoDatabase): CredentialDao = db.credentialDao()
    @Provides fun workflowGroupDao(db: KaivoDatabase): WorkflowGroupDao = db.workflowGroupDao()
    @Provides fun workflowVariableDao(db: KaivoDatabase): WorkflowVariableDao = db.workflowVariableDao()

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
    fun runRepository(dao: RunDao): RunRepository = RoomRunRepository(dao)

    @Provides @Singleton
    fun workflowGroupRepository(dao: WorkflowGroupDao): WorkflowGroupRepository = RoomWorkflowGroupRepository(dao)

    @Provides @Singleton
    fun workflowVariableRepository(dao: WorkflowVariableDao): WorkflowVariableRepository = RoomWorkflowVariableRepository(dao)

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
