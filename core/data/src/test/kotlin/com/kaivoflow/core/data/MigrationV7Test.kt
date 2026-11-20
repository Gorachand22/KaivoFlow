package com.kaivoflow.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * Schema 7 guards. The statements are compared with the exported `7.json` (what Room expects after the
 * migration) and, when a SQLite JDBC driver is on the classpath, actually executed on top of the schema-6
 * tables from `6.json`. The offline scratch runner does the same execution with Python's sqlite3
 * (scratch-w1-c/run_migration_sql.py), because no JDBC driver is available there.
 */
class MigrationV7Test {
    private fun schemaFile(v: Int): File {
        val rel = "schemas/com.kaivoflow.core.data.KaivoDatabase/$v.json"
        return listOfNotNull(File(rel), File("core/data/$rel"), File("../$rel"), System.getenv("KAIVO_CORE_DATA")?.let { File(it, rel) }).first { it.exists() }
    }

    private fun entities(v: Int): List<JsonObject> =
        Json.parseToJsonElement(schemaFile(v).readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    private val newTables = setOf("vault_meta", "agent_memory", "ai_usage", "ai_usage_rollup", "ai_event_log")

    private fun expectedStatements(): List<String> = entities(7).filter { it["tableName"]!!.jsonPrimitive.content in newTables }.flatMap { e ->
        val t = e["tableName"]!!.jsonPrimitive.content
        listOf(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t)) +
            (e["indices"] as JsonArray).map { it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t) }
    }

    @Test fun migrationStatementsEqualTheExportedSchemaSeven() {
        assertEquals(expectedStatements().toSet(), SCHEMA_7_STATEMENTS.toSet())
        assertEquals(SCHEMA_7_STATEMENTS.size, SCHEMA_7_STATEMENTS.toSet().size)
    }

    @Test fun schemaSevenKeepsEverySchemaSixTableUnchanged() {
        val six = entities(6).associateBy { it["tableName"]!!.jsonPrimitive.content }
        val seven = entities(7).associateBy { it["tableName"]!!.jsonPrimitive.content }
        six.forEach { (name, e) -> assertEquals("table $name must be untouched", e, seven[name]) }
        assertEquals(six.keys + newTables, seven.keys)
    }

    @Test fun schemaSevenIsDeclaredAsVersionSeven() {
        val v = Json.parseToJsonElement(schemaFile(7).readText()).jsonObject["database"]!!.jsonObject["version"]!!.jsonPrimitive.content
        assertEquals("7", v)
    }

    @Test fun statementsExecuteOnSchemaSixAndAreIdempotent() {
        val driver = runCatching { Class.forName("org.sqlite.JDBC") }.isSuccess
        assumeTrue("no SQLite JDBC driver on this classpath; run scratch-w1-c/run_migration_sql.py", driver)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                entities(6).forEach { e ->
                    val t = e["tableName"]!!.jsonPrimitive.content
                    st.execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t))
                }
                st.execute("INSERT INTO credentials (id, type, label, cipherText, createdAt) VALUES ('c', 'openAiApi', 'l', 'x', 1)")
                repeat(2) { SCHEMA_7_STATEMENTS.forEach { st.execute(it) } }
                st.executeQuery("SELECT COUNT(*) FROM credentials").use { assertEquals(1, it.getInt(1)) }
                newTables.forEach { t -> st.executeQuery("SELECT COUNT(*) FROM $t").use { assertTrue(it.next()) } }
            }
        }
    }
}
