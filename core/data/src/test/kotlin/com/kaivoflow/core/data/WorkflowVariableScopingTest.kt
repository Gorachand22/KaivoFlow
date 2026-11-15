package com.kaivoflow.core.data

import com.kaivoflow.core.model.WorkflowVariable
import kotlinx.coroutines.flow.Flow as FlowStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowVariableScopingTest {

    private class FakeWorkflowVariableDao : WorkflowVariableDao {
        val storage = mutableListOf<WorkflowVariableEntity>()

        override fun observeAll(): FlowStream<List<WorkflowVariableEntity>> =
            flowOf(storage.sortedBy { it.key })

        override fun observeForGroup(groupId: String): FlowStream<List<WorkflowVariableEntity>> =
            flowOf(storage.filter { it.groupId == groupId || it.groupId == null }.sortedBy { it.key })

        override suspend fun forGroup(groupId: String): List<WorkflowVariableEntity> =
            storage.filter { it.groupId == groupId || it.groupId == null }

        override suspend fun all(): List<WorkflowVariableEntity> = storage.toList()

        override suspend fun upsert(entity: WorkflowVariableEntity) {
            storage.removeAll { it.id == entity.id }
            storage.add(entity)
        }

        override suspend fun delete(id: String) {
            storage.removeAll { it.id == id }
        }
    }

    @Test
    fun `resolves global variables when groupId is null`() = runBlocking {
        val dao = FakeWorkflowVariableDao()
        val repo = RoomWorkflowVariableRepository(dao)

        repo.save(WorkflowVariable(id = "1", groupId = null, key = "API_HOST", value = "https://api.global.com"))
        repo.save(WorkflowVariable(id = "2", groupId = "group-a", key = "SECRET", value = "secret-a"))

        val resolved = repo.resolveVariables(null)
        assertEquals(mapOf("API_HOST" to "https://api.global.com"), resolved)
    }

    @Test
    fun `group variable overrides same-key global variable`() = runBlocking {
        val dao = FakeWorkflowVariableDao()
        val repo = RoomWorkflowVariableRepository(dao)

        repo.save(WorkflowVariable(id = "1", groupId = null, key = "CLIENT_ID", value = "global-client-id"))
        repo.save(WorkflowVariable(id = "2", groupId = null, key = "TIMEOUT", value = "30"))
        repo.save(WorkflowVariable(id = "3", groupId = "group-dental", key = "CLIENT_ID", value = "dental-client-id"))

        val resolved = repo.resolveVariables("group-dental")
        assertEquals(
            mapOf(
                "CLIENT_ID" to "dental-client-id", // Group overrides global!
                "TIMEOUT" to "30",
            ),
            resolved,
        )
    }

    @Test
    fun `isolated groups do not leak scoped variables to each other`() = runBlocking {
        val dao = FakeWorkflowVariableDao()
        val repo = RoomWorkflowVariableRepository(dao)

        repo.save(WorkflowVariable(id = "1", groupId = "group-1", key = "TOKEN", value = "token-1"))
        repo.save(WorkflowVariable(id = "2", groupId = "group-2", key = "TOKEN", value = "token-2"))

        val resolved1 = repo.resolveVariables("group-1")
        val resolved2 = repo.resolveVariables("group-2")

        assertEquals("token-1", resolved1["TOKEN"])
        assertEquals("token-2", resolved2["TOKEN"])
    }
}
