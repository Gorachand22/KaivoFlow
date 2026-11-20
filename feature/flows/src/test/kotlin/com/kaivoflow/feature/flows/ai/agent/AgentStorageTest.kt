package com.kaivoflow.feature.flows.ai.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentStorageTest {
    private fun dir(): File = Files.createTempDirectory("agents-test").toFile()

    private fun full() = CustomAgent(
        id = "a-1", name = "Brief", instructions = "{\"kx\":\"Hello {{ \$vars.name }}\"}",
        credentialId = "cred-1",
        triggers = listOf(AgentTrigger(id = "t1", type = "schedule", name = "Daily", config = AgentTriggerConfig.dailyAt("09:00") + ("objective" to "Say hi \"quoted\"\nnew line"))),
        tools = listOf(AgentTool(id = "x1", type = "device", name = "Notify", allowUnattended = true)),
        knowledge = listOf(AgentKnowledgeItem(id = "k1", title = "T", content = "line1\nline2 ☃")),
        skills = listOf(AgentSkill(id = "s1", name = "Skill")),
        settings = AgentSettings(maxTotalTokens = 20_000, maxToolCalls = 7, rateLimitPerTool = 3, deliverResult = "none"),
        sessions = listOf(AgentSession(id = "ss1", prompt = "p", response = "r")),
        crossSessionMemoryEnabled = true, createdAt = 5, updatedAt = 6,
    )

    @Test fun `a full agent round trips through the codec and carries the schema version`() {
        val a = full()
        val text = AgentJsonCodec.encode(a)
        assertTrue(text.contains("\"schemaVersion\":2"))
        val back = (AgentJsonCodec.decode(text) as AgentJsonCodec.Decoded.Ok).agent
        assertEquals(a, back)
    }

    @Test fun `a version 1 file loads, an old isPublished flag is not honoured and unknown fields are ignored`() {
        val v1 = """{"id":"old","name":"Old","isPublished":true,"somethingNew":[1,2],"triggers":[{"type":"schedule","name":"S","config":{"cron":"0 9 * * *"}}],"tools":[{"type":"device","name":"N"}],"settings":{"maxIterations":4}}"""
        val a = (AgentJsonCodec.decode(v1) as AgentJsonCodec.Decoded.Ok).agent
        assertEquals("Old", a.name)
        assertEquals(4, a.settings.maxIterations)
        assertEquals(50_000, a.settings.maxTotalTokens)          // new field gets its default
        assertFalse(a.tools.single().allowUnattended)            // consent is never inferred
        assertEquals("0 9 * * *", a.triggers.single().config["cron"])
        // Nothing in the model can say "live": the flag no longer exists, so the file can never make an agent look published.
        assertFalse(AgentJsonCodec.encode(a).contains("isPublished"))
    }

    @Test fun `bad input is reported, never thrown`() {
        fun bad(s: String) = AgentJsonCodec.decode(s) is AgentJsonCodec.Decoded.Bad
        assertTrue(bad("{not json"))
        assertTrue(bad("[1,2]"))
        assertTrue(bad("""{"name":"no id"}"""))
        assertTrue(bad("""{"id":"../../etc/passwd"}"""))
        assertTrue(bad("""{"id":"x","schemaVersion":99}"""))
    }

    @Test fun `write is atomic, leaves no temp file and reads back`() {
        val d = dir(); val store = AgentFileStore(d)
        store.write(full()); store.write(full().copy(name = "Second"))
        assertEquals(listOf("a-1.json"), d.list()!!.toList())
        assertEquals("Second", store.read("a-1")!!.name)
    }

    @Test fun `a crash that left only a temp file keeps the previous good file`() {
        val d = dir(); val store = AgentFileStore(d)
        store.write(full())
        File(d, "a-1.json.tmp").writeText("{half written")
        val report = store.load()
        assertEquals(listOf("a-1"), report.agents.map { it.id })
        assertFalse(File(d, "a-1.json.tmp").exists())
        assertTrue(report.quarantined.isEmpty())
    }

    @Test fun `a corrupt file is quarantined, reported, and the other agents still load`() {
        val d = dir(); val store = AgentFileStore(d) { 111L }
        store.write(full())
        File(d, "broken.json").writeText("{ this is not json")
        File(d, "mismatch.json").writeText(AgentJsonCodec.encode(full().copy(id = "other")))
        val report = store.load()
        assertEquals(listOf("a-1"), report.agents.map { it.id })
        assertEquals(2, report.quarantined.size)
        assertFalse(File(d, "broken.json").exists())
        assertTrue(File(d, "quarantine/broken.json.111").exists())
        // The second load is clean: the bad files are gone from the folder.
        assertTrue(store.load().quarantined.isEmpty())
    }

    @Test fun `ids that could escape the folder are refused`() {
        val d = dir(); val store = AgentFileStore(d)
        assertNull(store.read("../x"))
        runCatching { store.write(full().copy(id = "../x")) }.let { assertTrue(it.isFailure) }
    }

    @Test fun `repository loads off the constructor, seeds the sample once and survives parallel saves`() = runBlocking {
        val d = dir()
        val repo = AgentRepositoryImpl(AgentFileStore(d), starter = { CustomAgent(id = "starter", name = "Sample") })
        assertTrue(d.listFiles()!!.isEmpty())                       // constructing touched nothing
        val first = repo.agentById("starter")
        assertNotNull(first)
        // delete everything: the sample must not come back on the next start
        repo.deleteAgent("starter")
        val again = AgentRepositoryImpl(AgentFileStore(d), starter = { CustomAgent(id = "starter", name = "Sample") })
        assertNull(again.agentById("starter"))

        (1..40).map { i -> async { again.saveAgent(CustomAgent(id = "p$i", name = "n$i")) } }.awaitAll()
        val reloaded = AgentRepositoryImpl(AgentFileStore(d)).let { r -> (1..40).count { r.agentById("p$it") != null } }
        assertEquals(40, reloaded)
    }

    @Test fun `repository reports quarantined files and keeps working`() = runBlocking {
        val d = dir()
        File(d, "bad.json").writeText("nope")
        AgentFileStore(d).write(full())
        val repo = AgentRepositoryImpl(AgentFileStore(d))
        assertEquals(1, repo.quarantinedFiles().size)
        assertNotNull(repo.agentById("a-1"))
    }

    @Test fun `a duplicate is a new agent with no history`() = runBlocking {
        val repo = AgentRepositoryImpl(AgentFileStore(dir()), starter = { full() })
        val copy = repo.duplicateAgent("a-1")!!
        assertTrue(copy.id != "a-1" && copy.sessions.isEmpty() && copy.name.endsWith("(Copy)"))
        assertEquals(2, repo.observeAgents().first().size)
    }
}
