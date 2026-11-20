package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.ChannelStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.RawPort
import com.kaivoflow.core.engine.stepdata.StepDataPolicy
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.engine.stepdata.StepExecutionCapture
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RedactionAndFilesTest {
    private val secret = "live-secret-value-12345"
    private val source = object : SecretValueSource { override suspend fun values() = listOf(secret) }

    @Test fun maskerCoversTheShapesTheAuditListed() {
        val m = SecretMasker(listOf(secret))
        val samples = listOf(
            "failed with $secret here",
            "https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/sendMessage",
            "key sk_live_abcdefghijklmnop1234 rejected",
            "token ya29.a0AfH6SMBabcdefghijklmnopqrstuv",
            "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXX",
            "request header x-api-key: abcd1234efgh",
            "Authorization: Bearer abcdefghijklmnop",
            "GET /v1?api_key=abcdefghijk&x=1",
        )
        val out = samples.map(m::mask)
        assertFalse(out[0].contains(secret))
        assertFalse(out[1].contains("AAHdqTcv")); assertFalse(out[2].contains("abcdefghijklmnop1234"))
        assertFalse(out[3].contains("ya29.a0")); assertFalse(out[4].contains("XXXXXXXX"))
        assertFalse(out[5].contains("abcd1234efgh")); assertFalse(out[6].contains("abcdefghijklmnop"))
        assertFalse(out[7].contains("abcdefghijk&")); assertTrue(out[7].contains("x=1"))
        assertEquals("plain text stays", m.mask("plain text stays"))
    }

    private class MemRuns : RunRepository {
        var saved: RunRecord? = null
        override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<RunRecord>>()
        override fun observeForFlow(flowId: String, limit: Int) = kotlinx.coroutines.flow.emptyFlow<List<RunRecord>>()
        override suspend fun save(run: RunRecord) { saved = run }
        override suspend fun run(id: String) = saved
        override suspend fun clear() {}
        override suspend fun pruneOlderThan(days: Int) {}
    }

    @Test fun runErrorsAndStepErrorsAreMaskedBeforeSave() = runBlocking {
        val inner = MemRuns()
        val run = RunRecord("r", "f", "F", RunStatus.Failed, 1, 2, error = "Invalid API key: $secret",
            stepRuns = persistentListOf(StepRun("s", "S", StepRunStatus.Failed, 1, 2, error = "HTTP 401 echo $secret", ), StepRun("t", "T", StepRunStatus.Succeeded, 1, 2)))
        RedactingRunRepository(inner, source).save(run)
        val saved = inner.saved!!
        assertFalse(saved.error!!.contains(secret)); assertFalse(saved.stepRuns[0].error!!.contains(secret))
        assertEquals(null, saved.stepRuns[1].error); assertEquals("r", saved.id)
    }

    @Test fun runSaveStillMasksShapesWhenTheVaultCannotBeRead() = runBlocking {
        val inner = MemRuns()
        val broken = object : SecretValueSource { override suspend fun values(): List<String> = error("vault locked") }
        RedactingRunRepository(inner, broken).save(RunRecord("r", "f", "F", RunStatus.Failed, 1, error = "Authorization: Bearer abcdefghijklmnop"))
        assertFalse(inner.saved!!.error!!.contains("abcdefghijklmnop"))
    }

    @Test fun stepDataErrorsAndItemsAreMaskedWithRealSecretValues() = runBlocking {
        val store = StepDataStoreImpl(InMemoryStepDataAccess(), Dispatchers.Unconfined)
        val items = listOf(Item(json = mapOf("note" to "harmless key $secret inside", "n" to 1.0)))
        val raw = StepExecutionCapture(
            key = StepExecutionKey("run1", "step1", 0), flowId = "f", status = StepRunStatus.Failed, startedAt = 1, finishedAt = 2,
            inputs = emptyList(), outputs = listOf(RawPort(0, "main", items)),
            error = StepErrorInfo("boom $secret", details = "detail $secret", httpStatus = 401, httpBody = "{\"echo\":\"$secret\"}"), manual = true,
        )
        val got = ArrayList<CapturedExecution>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ got += it }, scope, StepDataPolicy())
        rec.record(raw); rec.flush(); rec.close(); scope.cancel()
        assertTrue("engine capture alone keeps the value", got.single().outputs[0].items[0].json.toString().contains(secret))
        RedactingStepDataStore(store, source).write(got.single())
        val sum = store.summary(StepExecutionKey("run1", "step1", 0))!!
        assertFalse(sum.error!!.message.contains(secret)); assertFalse(sum.error!!.details!!.contains(secret)); assertFalse(sum.error!!.httpBody!!.contains(secret))
        val page = store.page(StepExecutionKey("run1", "step1", 0), Direction.Out, 0, 0, 10)
        assertFalse(page.items.toString().contains(secret))
        assertEquals(401, sum.error!!.httpStatus)
    }

    // ---- files and session text ----

    @Test fun atomicWriteReplacesAndLeavesNoTempFile() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val f = File(dir, "s.json")
        AtomicFiles.write(f, "one"); AtomicFiles.write(f, "two")
        assertEquals("two", f.readText()); assertEquals(listOf("s.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun interruptedWriteLeavesTheOldFileIntact() {
        val dir = Files.createTempDirectory("atomic2").toFile()
        val f = File(dir, "s.json"); f.writeText("old")
        // Simulate a kill after the temp file was written but before the rename: only the .tmp is half-written.
        File(dir, "s.json.tmp").writeText("half")
        assertEquals("old", f.readText())
        AtomicFiles.write(f, "new")
        assertEquals("new", f.readText()); assertFalse(File(dir, "s.json.tmp").exists())
        dir.deleteRecursively()
    }

    @Test fun sessionCleanerRedactsAndBounds() {
        val c = SessionTextCleaner(listOf(secret), SessionLimits(maxMessagesPerSession = 3, maxCharsPerMessage = 20, maxTitleChars = 10, maxToolCallsPerMessage = 2))
        assertFalse(c.message("use $secret now").contains(secret))
        assertTrue(c.message("x".repeat(100)).length <= 21)
        assertEquals(10, c.title("a\nb".repeat(10)).length); assertFalse(c.title("a\nb").contains('\n'))
        assertEquals(listOf(3, 4, 5), c.window(listOf(1, 2, 3, 4, 5))); assertEquals(listOf(1, 2), c.toolCalls(listOf(1, 2, 3)))
    }
}
