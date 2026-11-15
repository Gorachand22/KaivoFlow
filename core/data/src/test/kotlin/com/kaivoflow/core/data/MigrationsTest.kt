package com.kaivoflow.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationsTest {
    private val current = 3

    @Test fun migrationsFormAContiguousChainFromOneToCurrent() {
        val sorted = ALL_MIGRATIONS.sortedBy { it.startVersion }
        assertEquals("first step must start at the oldest schema", 1, sorted.first().startVersion)
        sorted.forEach { assertEquals("each step advances one version", it.startVersion + 1, it.endVersion) }
        sorted.zipWithNext().forEach { (a, b) -> assertEquals("gap between steps", a.endVersion, b.startVersion) }
        assertEquals("chain must end at the database version", current, sorted.last().endVersion)
    }

    @Test fun noDuplicateSteps() {
        assertEquals(ALL_MIGRATIONS.size, ALL_MIGRATIONS.map { it.startVersion }.toSet().size)
        assertTrue(ALL_MIGRATIONS.isNotEmpty())
    }
}
