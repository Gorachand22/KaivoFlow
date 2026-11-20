package com.kaivoflow.feature.flows.ai.agent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileAgentRepository @Inject constructor(
    @ApplicationContext context: Context,
) : AgentRepository by AgentRepositoryImpl(
    store = AgentFileStore(File(context.filesDir, "agents")),
    log = { Log.w("KaivoAgents", it) },
)
