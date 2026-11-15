package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.ScopeBuilder
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Phase 1 (1.1, 1.2, 1.3, 1.12): per-item params, secret non-expansion, $vars, user file sandbox. */
class PerItemParamsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val two = listOf(item("name" to "Asha", "id" to "1"), item("name" to "Ravi", "id" to "2"))

    /** Runs through [NodeRegistry] exactly like the runner does, so the executor's spec is bound. */
    private fun run(executor: StepExecutor, params: Map<String, Any?>, input: List<FlowData>, http: RecordingHttp = RecordingHttp(), flow: Flow = Flow(id = "f", name = "Flow")): StepResult {
        val step = stepOf(executor.spec.id, params = params)
        val registry = NodeRegistry(listOf(executor))
        return runBlocking { registry.requireExecutor(step.type).execute(ctx(step, input, http = http, flow = flow)) }
    }

    @Test fun `http node sends a different url, header and body per item`() {
        val http = RecordingHttp()
        run(
            HttpNode(),
            mapOf(
                "url" to "https://example.test/u/{{ \$json.id }}",
                "headers" to mapOf("X-Name" to "{{ \$json.name }}"),
                "bodyKind" to "json",
                "body" to mapOf("who" to "{{ \$json.name }}"),
                "method" to "POST",
            ),
            two, http,
        )
        assertEquals(listOf("https://example.test/u/1", "https://example.test/u/2"), http.requests.map { it.url })
        assertEquals(listOf("Asha", "Ravi"), http.requests.map { it.headers["X-Name"] })
        assertEquals(listOf("Asha", "Ravi"), http.requests.map { (it.body as Map<*, *>)["who"] })
    }

    @Test fun `slack and discord messages differ per item`() {
        val slackHttp = RecordingHttp()
        run(SlackNode(), mapOf("webhookUrl" to "https://hooks.test/x", "text" to "Hi {{ \$json.name }}", "channel" to "#{{ \$json.id }}"), two, slackHttp)
        assertEquals(2, slackHttp.requests.size)
        assertTrue(slackHttp.requests[0].body.toString().contains("Hi Asha"))
        assertTrue(slackHttp.requests[1].body.toString().contains("Hi Ravi"))
        assertTrue(slackHttp.requests[1].body.toString().contains("#2"))

        val discordHttp = RecordingHttp()
        run(DiscordNode(), mapOf("webhookUrl" to "https://hooks.test/d", "content" to "Yo {{ \$json.name }}"), two, discordHttp)
        assertTrue(discordHttp.requests[0].body.toString().contains("Yo Asha"))
        assertTrue(discordHttp.requests[1].body.toString().contains("Yo Ravi"))
    }

    @Test fun `telegram sends per item chat and text`() {
        val http = RecordingHttp()
        run(TelegramNode(), mapOf("credential" to "cred-1", "chatId" to "{{ \$json.id }}", "text" to "{{ \$json.name }}"), two, http)
        val bodies = http.requests.map { it.body.toString() }
        assertTrue(bodies[0].contains("Asha") && bodies[0].contains("1"))
        assertTrue(bodies[1].contains("Ravi") && bodies[1].contains("2"))
    }

    @Test fun `sms and notification differ per item`() {
        val devices = RecordingDevices()
        run(SmsSendNode(devices), mapOf("to" to "{{ \$json.id }}", "body" to "Hello {{ \$json.name }}"), two)
        assertEquals(listOf("1" to "Hello Asha", "2" to "Hello Ravi"), devices.sms)
        val d2 = RecordingDevices()
        run(NotificationSendNode(d2), mapOf("title" to "T {{ \$json.name }}", "body" to "B {{ \$json.id }}"), two)
        assertEquals(listOf("T Asha", "T Ravi"), d2.notifications.map { it.first })
        assertEquals(listOf("B 1", "B 2"), d2.notifications.map { it.second })
    }

    @Test fun `set text template date and crypto resolve per item`() {
        val text = run(TemplateNode(), mapOf("template" to "Dear {{ \$json.name }}", "outputField" to "text"), two)
        assertEquals(listOf("Dear Asha", "Dear Ravi"), text.primary.map { it.json["text"] })

        val hash = run(CryptoNode(), mapOf("action" to "sha256", "input" to "{{ \$json.name }}", "outputField" to "h"), two)
        val hashes = hash.primary.map { it.json["h"] }
        assertEquals(2, hashes.toSet().size)

        val set = run(SetFieldsNode(), mapOf("fields" to mapOf("greeting" to "Hi {{ \$json.name }}")), two)
        assertEquals(listOf("Hi Asha", "Hi Ravi"), set.primary.map { it.json["greeting"] })

        val dates = run(
            DateNode(),
            mapOf("action" to "format", "source" to "{{ \$json.d }}", "pattern" to "yyyy-MM-dd", "outputField" to "out"),
            listOf(item("d" to "2024-01-02T00:00:00Z"), item("d" to "2025-03-04T00:00:00Z")),
        )
        assertEquals(2, dates.primary.map { it.json["out"] }.toSet().size)
    }

    @Test fun `expression results containing braces are not evaluated a second time`() {
        val out = run(TemplateNode(), mapOf("template" to "{{ \$json.name }}", "outputField" to "t"), listOf(item("name" to "{{ \$vars.secret }}")), flow = Flow(id = "f", name = "F", vars = persistentMapOf("secret" to "LEAK")))
        assertEquals("{{ \$vars.secret }}", out.primary.single().json["t"])
    }

    @Test fun `secret params are never expression expanded`() {
        val http = RecordingHttp()
        run(
            HttpNode(),
            mapOf("url" to "https://example.test/", "auth" to "bearer", "authToken" to "{{ \$json.name }}"),
            listOf(item("name" to "leaky")), http,
        )
        assertEquals("Bearer {{ \$json.name }}", http.requests.single().headers["Authorization"])

        val slackHttp = RecordingHttp()
        run(SlackNode(), mapOf("webhookUrl" to "https://hooks.test/{{ \$json.name }}", "text" to "x"), listOf(item("name" to "leaky")), slackHttp)
        assertEquals("https://hooks.test/{{ \$json.name }}", slackHttp.requests.single().url)
    }

    @Test fun `code field is never expression expanded`() {
        val node = CodeNode()
        val key = node.spec.params.first { !it.supportsExpression && it.key != "mode" }.key
        val params = stepOf(node.spec.id, params = mapOf(key to "return '{{ \$json.name }}'"))
        val resolved = com.kaivoflow.core.engine.ParamResolver(TestEvaluator()).resolveParams(params, mapOf("json" to mapOf("name" to "x")), node.spec)
        assertEquals("return '{{ \$json.name }}'", resolved[key])
    }

    @Test fun `vars come from the flow and env is gone`() {
        val flow = Flow(id = "f", name = "F", vars = persistentMapOf("greeting" to "hello"))
        val scope = ScopeBuilder(flow.vars, "r", emptyMap()).build(listOf(item("a" to 1)))
        assertEquals(mapOf("greeting" to "hello"), scope["vars"])
        assertFalse(scope.containsKey("env"))

        val out = run(TemplateNode(), mapOf("template" to "{{ \$vars.greeting }}", "outputField" to "t"), listOf(item("a" to 1)), flow = flow)
        assertEquals("hello", out.primary.single().json["t"])
        val env = run(TemplateNode(), mapOf("template" to "{{ \$env.PATH }}", "outputField" to "t"), listOf(item("a" to 1)), flow = flow)
        assertNull(env.primary.single().json["t"])
    }

    @Test fun `user file store rejects traversal absolute paths and symlink escape`() {
        val root = tmp.newFolder("user-files")
        val outside = tmp.newFolder("flows")
        val store = UserFileStore(root)
        assertEquals("hi", store.write("a/b.txt", "hi").let { store.read("a/b.txt") })
        assertTrue(File(root, "a/b.txt").exists())
        assertNull(store.read("missing.txt"))
        for (bad in listOf("../flows/x.json", "a/../../x", "/etc/passwd", "C:\\x", "..\\x", "", "a\u0000b")) {
            assertThrows(bad, IllegalArgumentException::class.java) { store.resolve(bad) }
        }
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.write("link/stolen.txt", "x") }
        assertFalse(File(outside, "stolen.txt").exists())
    }

    @Test fun `file node refuses a path that leaves its folder`() {
        val devices = RecordingDevices()
        val ex = assertThrows(StepExecutionException::class.java) {
            run(FileNode(devices), mapOf("action" to "write", "fileName" to "../flows/x.json", "content" to "x"), two)
        }
        assertTrue(ex.message!!.contains(".."))
        assertTrue(devices.files.isEmpty())
    }
}
