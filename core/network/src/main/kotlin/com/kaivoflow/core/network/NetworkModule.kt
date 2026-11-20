package com.kaivoflow.core.network

import com.kaivoflow.core.engine.HttpGateway
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(allowList: HostAllowList): HttpClient = KtorHttpGateway.createClient(allowList)

    /** The one list of local hosts the person allowed. [MutableHostAllowList] is the same object, for the screens that add to it. */
    @Provides
    @Singleton
    fun provideAllowList(@ApplicationContext context: Context): PrefsHostAllowList = PrefsHostAllowList(context)

    @Provides
    fun hostAllowList(list: PrefsHostAllowList): HostAllowList = list

    /** Only loopback and LAN hosts can be added this way; public, link-local and reserved hosts are ignored. */
    @Provides
    fun localHostRegistrar(list: PrefsHostAllowList): com.kaivoflow.core.model.LocalHostRegistrar =
        com.kaivoflow.core.model.LocalHostRegistrar { baseUrl -> list.allowLocalFromUrl(baseUrl) }

    @Provides
    fun mutableHostAllowList(list: PrefsHostAllowList): MutableHostAllowList = list
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindings {

    @Binds
    abstract fun httpGateway(impl: KtorHttpGateway): HttpGateway
}
