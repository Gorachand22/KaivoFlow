package com.kaivoflow.core.data

import com.kaivoflow.core.model.Flow
import kotlinx.coroutines.flow.Flow as FlowStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomFlowRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fakeDao = object : FlowDao {
        val map = mutableMapOf<String, FlowEntity>()
        override fun observeAll(): FlowStream<List<FlowEntity>> = flowOf(map.values.toList())
        override fun observe(id: String): FlowStream<FlowEntity?> = flowOf(map[id])
        override suspend fun byId(id: String): FlowEntity? = map[id]
        override suspend fun all(): List<FlowEntity> = map.values.toList()
        override suspend fun upsert(entity: FlowEntity) { map[entity.id] = entity }
        override suspend fun delete(id: String) { map.remove(id) }
        override suspend fun updateLastRun(id: String, status: String, at: Long) {}
        override suspend fun setFavorite(id: String, favorite: Boolean) {}
        override suspend fun updateGroup(id: String, groupId: String?) {
            map[id]?.let { map[id] = it.copy(groupId = groupId) }
        }
    }

    @Test
    fun `save mirrors flow JSON to storageDir and delete removes it`() = runBlocking {
        val storage = tempFolder.root
        val repo = RoomFlowRepository(fakeDao, storageDir = storage)
        val flow = Flow(id = "test-123", name = "Test Flow")

        repo.save(flow)

        val file = storage.resolve("flows").resolve("test-123.json")
        assertTrue("Flow file should exist in internal storage mirror", file.exists())
        val content = file.readText()
        assertTrue(content.contains("test-123"))
        assertTrue(content.contains("Test Flow"))

        repo.delete("test-123")
        assertFalse("Flow file should be removed on delete", file.exists())
    }

    @Test
    fun `saving never changes the lifecycle and isActive mirrors the column`() = runBlocking {
        val repo = RoomFlowRepository(fakeDao)
        repo.save(Flow(id = "a", name = "A", isActive = true))
        // A new row is Draft whatever the Flow object says (imports and duplicates are always Draft).
        assertEquals("DRAFT", fakeDao.map["a"]!!.lifecycle)
        assertFalse(repo.flow("a")!!.isActive)
        // Once the state machine publishes, a later edit-and-save keeps it, and the mirror shows it.
        fakeDao.map["a"] = fakeDao.map["a"]!!.copy(lifecycle = "PUBLISHED", publishedRevision = 3)
        repo.save(repo.flow("a")!!.copy(name = "A2", isActive = false))
        assertEquals("PUBLISHED", fakeDao.map["a"]!!.lifecycle)
        assertEquals(3, fakeDao.map["a"]!!.publishedRevision)
        assertTrue(repo.flow("a")!!.isActive)
        val s = repo.observeSummaries().let { f -> f.first() }.single()
        assertEquals(com.kaivoflow.core.engine.LifecycleState.Published, s.lifecycle)
        assertTrue(s.isLive)
    }
}
