package com.kaivoflow.core.data

import android.content.Context
import androidx.room.Room
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
            // Schema is version 1; migrations are added here as the model evolves.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun flowDao(db: KaivoDatabase): FlowDao = db.flowDao()
    @Provides fun runDao(db: KaivoDatabase): RunDao = db.runDao()
    @Provides fun credentialDao(db: KaivoDatabase): CredentialDao = db.credentialDao()

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
    fun flowRepository(dao: FlowDao): FlowRepository = RoomFlowRepository(dao)

    @Provides @Singleton
    fun runRepository(dao: RunDao): RunRepository = RoomRunRepository(dao)

    @Provides @Singleton
    fun credentialRepository(dao: CredentialDao, cryptoBox: CryptoBox): CredentialRepository =
        EncryptedCredentialRepository(dao, cryptoBox)
}
