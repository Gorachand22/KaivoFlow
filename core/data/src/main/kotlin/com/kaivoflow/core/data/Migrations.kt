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

/**
 * Schema 4 (Always-On Engine, spec section 4.5). Every statement is idempotent. The CREATE statements
 * must stay character-compatible with what Room generates for the entities in EngineEntities.kt,
 * otherwise the post-migration schema validation throws on first open.
 */
private fun SupportSQLiteDatabase.createEngineTables() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `flow_versions` (`flowId` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
            "`savedAt` INTEGER NOT NULL, `label` TEXT NOT NULL, `flowJson` TEXT NOT NULL, PRIMARY KEY(`flowId`, `revision`))",
    )
    execSQL(
        "CREATE TABLE IF NOT EXISTS `trigger_registration` (`flowId` TEXT NOT NULL, `sparkId` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, `state` TEXT NOT NULL, `detail` TEXT, `since` INTEGER NOT NULL, " +
            "`retryCount` INTEGER NOT NULL, `nextRetryAt` INTEGER, PRIMARY KEY(`flowId`, `sparkId`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_trigger_registration_state` ON `trigger_registration` (`state`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `flow_lifecycle_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`flowId` TEXT NOT NULL, `at` INTEGER NOT NULL, `fromState` TEXT NOT NULL, `toState` TEXT NOT NULL, " +
            "`reason` TEXT NOT NULL, `actor` TEXT NOT NULL)",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_flow_lifecycle_log_flowId_at` ON `flow_lifecycle_log` (`flowId`, `at`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `spark_inbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`flowId` TEXT NOT NULL, `sparkId` TEXT NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT, `fileRef` TEXT, " +
            "`receivedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, `runId` TEXT, " +
            "`leaseUntil` INTEGER, `dropReason` TEXT)",
    )
    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spark_inbox_dedupeKey` ON `spark_inbox` (`dedupeKey`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_spark_inbox_state_receivedAt` ON `spark_inbox` (`state`, `receivedAt`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_spark_inbox_flowId` ON `spark_inbox` (`flowId`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `schedule_state` (`flowId` TEXT NOT NULL, `sparkId` TEXT NOT NULL, " +
            "`configHash` TEXT NOT NULL, `precision` TEXT NOT NULL, `nextDueAt` INTEGER, `lastFiredAt` INTEGER, " +
            "`lastSlotKey` TEXT, `armedAt` INTEGER, `armedMode` TEXT, `missedPolicy` TEXT NOT NULL, `lastStatus` TEXT, " +
            "PRIMARY KEY(`flowId`, `sparkId`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_state_nextDueAt` ON `schedule_state` (`nextDueAt`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `expected_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`flowId` TEXT NOT NULL, `sparkId` TEXT NOT NULL, `slotKey` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, " +
            "`armedAt` INTEGER NOT NULL, `state` TEXT NOT NULL, `firedAt` INTEGER, `runId` TEXT, `latenessMs` INTEGER, " +
            "`reason` TEXT)",
    )
    execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_expected_run_flowId_sparkId_slotKey` ON `expected_run` (`flowId`, `sparkId`, `slotKey`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_expected_run_state_dueAt` ON `expected_run` (`state`, `dueAt`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_expected_run_dueAt` ON `expected_run` (`dueAt`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `engine_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`startedAt` INTEGER NOT NULL, `startReason` TEXT NOT NULL, `bootCount` INTEGER NOT NULL, " +
            "`cleanStopAt` INTEGER, `endReason` TEXT)",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_engine_session_startedAt` ON `engine_session` (`startedAt`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `engine_heartbeat` (`id` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, " +
            "`lastBeatWall` INTEGER NOT NULL, `lastBeatElapsed` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, " +
            "`processStart` INTEGER NOT NULL, `engineState` TEXT NOT NULL, `pendingInbox` INTEGER NOT NULL, " +
            "`armedAlarms` INTEGER NOT NULL, `listenerLastConnected` INTEGER, `listenerLastDisconnected` INTEGER, " +
            "`lastAlarmFiredAt` INTEGER, `wakeLockMillisToday` INTEGER NOT NULL, `memoryClass` INTEGER NOT NULL, " +
            "`appVersion` TEXT NOT NULL, PRIMARY KEY(`id`))",
    )
    execSQL(
        "CREATE TABLE IF NOT EXISTS `reliability_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `severity` TEXT NOT NULL, `flowId` TEXT, `detail` TEXT, " +
            "`sessionId` INTEGER)",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_reliability_event_at` ON `reliability_event` (`at`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_reliability_event_kind_at` ON `reliability_event` (`kind`, `at`)")
}

/** Adds the lifecycle columns to `flows`. Returns true when the lifecycle column was newly added. */
private fun SupportSQLiteDatabase.addFlowLifecycleColumns(): Boolean {
    val fresh = !hasColumn("flows", "lifecycle")
    if (fresh) execSQL("ALTER TABLE `flows` ADD COLUMN `lifecycle` TEXT NOT NULL DEFAULT 'DRAFT'")
    if (!hasColumn("flows", "publishedRevision")) execSQL("ALTER TABLE `flows` ADD COLUMN `publishedRevision` INTEGER")
    if (!hasColumn("flows", "lifecycleReason")) execSQL("ALTER TABLE `flows` ADD COLUMN `lifecycleReason` TEXT NOT NULL DEFAULT 'NONE'")
    if (!hasColumn("flows", "lifecycleSince")) execSQL("ALTER TABLE `flows` ADD COLUMN `lifecycleSince` INTEGER NOT NULL DEFAULT 0")
    if (!hasColumn("flows", "pausedUntil")) execSQL("ALTER TABLE `flows` ADD COLUMN `pausedUntil` INTEGER")
    if (!hasColumn("flows", "errorCode")) execSQL("ALTER TABLE `flows` ADD COLUMN `errorCode` TEXT")
    if (!hasColumn("flows", "errorSince")) execSQL("ALTER TABLE `flows` ADD COLUMN `errorSince` INTEGER")
    if (!hasColumn("flows", "retryCount")) execSQL("ALTER TABLE `flows` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
    if (!hasColumn("flows", "nextRetryAt")) execSQL("ALTER TABLE `flows` ADD COLUMN `nextRetryAt` INTEGER")
    if (!hasColumn("flows", "healthySince")) execSQL("ALTER TABLE `flows` ADD COLUMN `healthySince` INTEGER")
    execSQL("CREATE INDEX IF NOT EXISTS `index_flows_lifecycle` ON `flows` (`lifecycle`)")
    return fresh
}

/**
 * Previously active Flows (the flag lives inside the JSON document) become Published with their
 * current document saved as revision 1; everything else keeps the column default, Draft. Rows are
 * read fully before any write so no cursor is open during the updates.
 */
private fun SupportSQLiteDatabase.mapLegacyActiveFlows() {
    class Row(val id: String, val json: String, val updatedAt: Long)
    val active = mutableListOf<Row>()
    query("SELECT `id`, `flowJson`, `updatedAt` FROM `flows`").use { c ->
        while (c.moveToNext()) {
            val json = c.getString(1)
            if (LifecycleLogic.isActiveInJson(json)) active += Row(c.getString(0), json, c.getLong(2))
        }
    }
    for (row in active) {
        execSQL(
            "UPDATE `flows` SET `lifecycle` = 'PUBLISHED', `publishedRevision` = 1, `lifecycleReason` = 'MIGRATED_ACTIVE', " +
                "`lifecycleSince` = ? WHERE `id` = ?",
            arrayOf<Any>(row.updatedAt, row.id),
        )
        execSQL(
            "INSERT OR IGNORE INTO `flow_versions` (`flowId`, `revision`, `savedAt`, `label`, `flowJson`) VALUES (?, 1, ?, ?, ?)",
            arrayOf<Any>(row.id, row.updatedAt, "Migrated from schema 3", row.json),
        )
        execSQL(
            "INSERT INTO `flow_lifecycle_log` (`flowId`, `at`, `fromState`, `toState`, `reason`, `actor`) " +
                "VALUES (?, ?, 'DRAFT', 'PUBLISHED', 'MIGRATED_ACTIVE', 'engine')",
            arrayOf<Any>(row.id, row.updatedAt),
        )
    }
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

/**
 * v3 -> v4: publish lifecycle columns on `flows` and the Always-On Engine tables. Additive only.
 * Order matters: tables first (the data pass writes `flow_versions` and `flow_lifecycle_log`), then
 * columns, then the data pass, and the data pass runs only when the lifecycle column was new.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.createEngineTables()
        val fresh = db.addFlowLifecycleColumns()
        if (fresh) db.mapLegacyActiveFlows()
    }
}

/**
 * Schema 5 (Step execution data and pins, spec P2-data-panels-params section 5.2). The statements must
 * stay equivalent to what Room generates for StepDataEntities.kt: same columns, nullability, primary
 * keys, foreign key and index names, or the post-migration schema validation throws on first open.
 * Idempotent (IF NOT EXISTS); nothing existing is touched. Existing Runs keep their `runJson`; they simply
 * have no stored Step data (the panels show "data not captured" for them).
 */
internal fun SupportSQLiteDatabase.createStepDataTables() {
    execSQL(
        "CREATE TABLE IF NOT EXISTS `step_executions` (`runId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `runIndex` INTEGER NOT NULL, " +
            "`flowId` TEXT NOT NULL, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, " +
            "`attempts` INTEGER NOT NULL, `errorJson` TEXT, `pinned` INTEGER NOT NULL, `manual` INTEGER NOT NULL, " +
            "`dataDropped` INTEGER NOT NULL, PRIMARY KEY(`runId`, `stepId`, `runIndex`))",
    )
    execSQL("CREATE INDEX IF NOT EXISTS `index_step_executions_stepId_startedAt` ON `step_executions` (`stepId`, `startedAt`)")
    execSQL("CREATE INDEX IF NOT EXISTS `index_step_executions_flowId_startedAt` ON `step_executions` (`flowId`, `startedAt`)")
    execSQL(
        "CREATE TABLE IF NOT EXISTS `step_port_data` (`runId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `runIndex` INTEGER NOT NULL, " +
            "`direction` TEXT NOT NULL, `port` INTEGER NOT NULL, `label` TEXT, `itemCount` INTEGER NOT NULL, " +
            "`storedCount` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, `truncationJson` TEXT, `schemaJson` TEXT, " +
            "`parentsJson` TEXT NOT NULL, `itemsBlob` BLOB NOT NULL, `offsetsBlob` BLOB NOT NULL, " +
            "PRIMARY KEY(`runId`, `stepId`, `runIndex`, `direction`, `port`), " +
            "FOREIGN KEY(`runId`, `stepId`, `runIndex`) REFERENCES `step_executions`(`runId`, `stepId`, `runIndex`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
    execSQL(
        "CREATE TABLE IF NOT EXISTS `step_pins` (`flowId` TEXT NOT NULL, `stepId` TEXT NOT NULL, `portsJson` TEXT NOT NULL, " +
            "`bytes` INTEGER NOT NULL, `pinnedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`flowId`, `stepId`))",
    )
}

/** v4 -> v5: Step execution data tables and pins. Additive only. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.createStepDataTables()
    }
}

/** Ordered, contiguous list from the oldest known schema to [KaivoDatabase] current version. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
