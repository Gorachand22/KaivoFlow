package com.kaivoflow.feature.flows.ai.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

interface AgentRepository {
    fun observeAgents(): Flow<List<CustomAgent>>
    suspend fun agentById(id: String): CustomAgent?
    suspend fun saveAgent(agent: CustomAgent)
    suspend fun deleteAgent(id: String)
    suspend fun duplicateAgent(id: String): CustomAgent?

    /** Files that could not be read and were moved aside, one readable line each. Empty when nothing was wrong. */
    suspend fun quarantinedFiles(): List<String> = emptyList()
}

/**
 * Agents on disk through [AgentFileStore]. Nothing touches the disk on the main thread or in the constructor: the first reader
 * loads on IO. One mutex orders every read and write, so two quick saves cannot interleave or lose each other.
 */
class AgentRepositoryImpl(
    private val store: AgentFileStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val starter: () -> CustomAgent = ::starterAgent,
    private val log: (String) -> Unit = {},
) : AgentRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<CustomAgent>>(emptyList())
    private var loaded = false
    private var quarantined: List<String> = emptyList()

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(io) {
                val report = store.load()
                quarantined = report.quarantined
                report.quarantined.forEach { log("agent file quarantined: $it") }
                var list = report.agents
                if (list.isEmpty() && !report.hadAnyFile && !store.seeded()) {
                    val s = starter()
                    runCatching { store.write(s); store.markSeeded(); list = listOf(s) }
                } else if (!store.seeded()) {
                    runCatching { store.markSeeded() }
                }
                state.value = list
            }
            loaded = true
        }
    }

    override fun observeAgents(): Flow<List<CustomAgent>> = flow {
        ensureLoaded()
        emitAll(state)
    }

    override suspend fun agentById(id: String): CustomAgent? {
        ensureLoaded()
        return mutex.withLock { state.value.firstOrNull { it.id == id } ?: withContext(io) { store.read(id) } }
    }

    override suspend fun saveAgent(agent: CustomAgent) {
        ensureLoaded()
        mutex.withLock {
            val updated = agent.copy(updatedAt = System.currentTimeMillis())
            withContext(io) { store.write(updated) }
            putLocked(updated)
        }
    }

    override suspend fun deleteAgent(id: String) {
        ensureLoaded()
        mutex.withLock {
            withContext(io) { store.delete(id) }
            state.value = state.value.filterNot { it.id == id }
        }
    }

    override suspend fun duplicateAgent(id: String): CustomAgent? {
        val original = agentById(id) ?: return null
        val now = System.currentTimeMillis()
        // A copy is a new agent: it has its own id, so it is never live (nothing is published under it) and has no test history.
        val copy = original.copy(id = UUID.randomUUID().toString(), name = "${original.name} (Copy)", createdAt = now, updatedAt = now, sessions = emptyList())
        saveAgent(copy)
        return copy
    }

    override suspend fun quarantinedFiles(): List<String> { ensureLoaded(); return quarantined }

    private fun putLocked(a: CustomAgent) {
        val cur = state.value.toMutableList()
        val i = cur.indexOfFirst { it.id == a.id }
        if (i >= 0) cur[i] = a else cur.add(0, a)
        state.value = cur
    }
}

fun starterAgent(): CustomAgent = CustomAgent(
    id = "starter_morning_news",
    name = "Morning News Brief",
    iconEmoji = "☀️",
    iconColorHex = 0xFFFF8A00,
    instructions = "You are a morning brief agent. Compile a short morning brief for the person: a greeting, today's date, and up to five points worth their attention. You have no web access, so say plainly when you do not know something instead of guessing.",
    triggers = listOf(
        AgentTrigger(
            type = "schedule",
            name = "Daily 9:00 AM Spark",
            description = "Runs automatically every morning at 9am",
            config = AgentTriggerConfig.dailyAt("09:00"),
            isEnabled = true,
        ),
    ),
    tools = listOf(
        AgentTool(type = "device", name = "Phone Notification", description = "Sends the brief summary to your phone notifications", isEnabled = true),
    ),
)
