package com.kaivoflow.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations. There is no destructive fallback: a missing step makes Room throw
 * instead of wiping user data.
 *
 * Only the version 1 schema JSON was ever committed, so the split of changes between 2 and 3 is
 * inferred from the v1 schema versus the current entities. Every step is therefore idempotent
 * (IF NOT EXISTS, and ALTER only when the column is absent), which is correct whichever way the
 * historical v2 build shaped its tables. All steps are additive; no data is dropped or rewritten.
 */
internal fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { c ->
        val nameIdx = c.getColumnIndex("name")
        var found = false
        while (c.moveToNext()) if (c.getString(nameIdx) == column) { found = true; break }
        found
    }

private fun SupportSQLiteDatabase.addFlowGroupId() {
    if (!hasColumn("flows", "groupId")) execSQL("ALTER TABLE `flows` ADD COLUMN `groupId` TEXT")
    execSQL("CREATE INDEX IF NOT EXISTS `index_flows_groupId` ON `flows` (`groupId`)")
}

private fun SupportSQLiteDatabase.createGroupsAndVariables() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `workflow_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `color` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_groups_name` ON `workflow_groups` (`name`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `workflow_variables` (`id` TEXT NOT NULL, `groupId` TEXT, " +
            "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_variables_groupId` ON `workflow_variables` (`groupId`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_variables_key` ON `workflow_variables` (`key`)")
}

private fun SupportSQLiteDatabase.createDataTables() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `data_tables` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `columnsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_data_tables_name` ON `data_tables` (`name`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `data_table_rows` (`id` TEXT NOT NULL, `tableId` TEXT NOT NULL, " +
            "`dataJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_data_table_rows_tableId` ON `data_table_rows` (`tableId`)")
}

/** v1 -> v2: flow groups, workflow variables, flows.groupId (inferred, see file note). */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addFlowGroupId()
        db.createGroupsAndVariables()
    }
}

/** v2 -> v3: data tables and rows (inferred). Also re-asserts the v2 objects idempotently. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addFlowGroupId()
        db.createGroupsAndVariables()
        db.createDataTables()
    }
}

/** Ordered, contiguous list from the oldest known schema to [KaivoDatabase] current version. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
