package com.kaivoflow.core.data

import com.kaivoflow.core.model.DataTableColumn
import kotlinx.coroutines.flow.Flow as FlowStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DataTableRepositoryTest {

    private class FakeDataTableDao : DataTableDao {
        val tables = mutableListOf<DataTableEntity>()
        val rows = mutableListOf<DataTableRowEntity>()

        override fun observeAll(): FlowStream<List<DataTableEntity>> =
            flowOf(tables.sortedByDescending { it.updatedAt })

        override suspend fun byId(id: String): DataTableEntity? =
            tables.firstOrNull { it.id == id }

        override suspend fun upsertTable(table: DataTableEntity) {
            tables.removeAll { it.id == table.id }
            tables.add(table)
        }

        override suspend fun deleteTable(id: String) {
            tables.removeAll { it.id == id }
        }

        override fun observeRows(tableId: String): FlowStream<List<DataTableRowEntity>> =
            flowOf(rows.filter { it.tableId == tableId }.sortedBy { it.createdAt })

        override suspend fun rowsForTable(tableId: String): List<DataTableRowEntity> =
            rows.filter { it.tableId == tableId }.sortedBy { it.createdAt }

        override fun observeRowCount(tableId: String): FlowStream<Int> =
            flowOf(rows.count { it.tableId == tableId })

        override suspend fun rowCount(tableId: String): Int =
            rows.count { it.tableId == tableId }

        override suspend fun upsertRow(row: DataTableRowEntity) {
            rows.removeAll { it.id == row.id }
            rows.add(row)
        }

        override suspend fun deleteRow(rowId: String) {
            rows.removeAll { it.id == rowId }
        }

        override suspend fun deleteRowsForTable(tableId: String) {
            rows.removeAll { it.tableId == tableId }
        }
    }

    @Test
    fun `create table and insert rows`() = runBlocking {
        val dao = FakeDataTableDao()
        val repository = RoomDataTableRepository(dao)

        val columns = listOf(
            DataTableColumn(id = "c1", name = "Name", type = "string"),
            DataTableColumn(id = "c2", name = "Age", type = "number"),
            DataTableColumn(id = "c3", name = "Active", type = "boolean"),
        )
        val tableId = repository.createTable("Customers", "Customer leads", columns)
        assertNotNull(tableId)

        val table = repository.tableById(tableId)
        assertNotNull(table)
        assertEquals("Customers", table?.name)
        assertEquals(3, table?.columns?.size)

        val rowId1 = repository.insertRow(tableId, mapOf("Name" to "Alice", "Age" to 30.0, "Active" to true))
        val rowId2 = repository.insertRow(tableId, mapOf("Name" to "Bob", "Age" to 25.0, "Active" to false))
        assertNotNull(rowId1)
        assertNotNull(rowId2)

        val rows = repository.rowsForTable(tableId)
        assertEquals(2, rows.size)
        assertEquals("Alice", rows[0].data["Name"])
        assertEquals("Bob", rows[1].data["Name"])

        val tables = repository.observeTables().first()
        assertEquals(1, tables.size)
        assertEquals(2, tables[0].rowCount)

        repository.deleteRow(rowId1)
        val updatedRows = repository.rowsForTable(tableId)
        assertEquals(1, updatedRows.size)
        assertEquals("Bob", updatedRows[0].data["Name"])

        repository.deleteTable(tableId)
        assertNull(repository.tableById(tableId))
        assertEquals(0, repository.rowsForTable(tableId).size)
    }
}
