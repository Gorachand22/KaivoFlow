package com.kaivoflow.core.data

import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `.kaivoflow` backup must restore everything except secrets and local file handles. */
class NativeFlowBundleTest {

    private val known = setOf("spark.manual", "net.http", "device.notify", "spark.geofence")

    private fun sample() = Flow(
        id = "flow-1",
        name = "Morning digest",
        tags = persistentListOf("daily", "personal"),
        vars = persistentMapOf("city" to "Bhubaneswar", "apiToken" to "DO-NOT-SHIP"),
        steps = persistentListOf(
            StepNode(id = "t", type = "spark.manual", name = "Start", x = 12f, y = 4f),
            StepNode(
                id = "h",
                type = "net.http",
                name = "Fetch",
                x = 40f,
                y = 120f,
                notes = "Review the URL before sharing",
                params = persistentMapOf(
                    "url" to "https://example.com/api",
                    "method" to "GET",
                    "authToken" to "SECRET-123",
                    "headers" to mapOf("Accept" to "application/json", "X-Key" to "ALSO-SECRET"),
                ),
                frozenOutput = persistentListOf(
                    FlowData(
                        json = mapOf("ok" to true),
                        binary = mapOf(
                            "photo" to BinaryDataRef(
                                uri = "content://media/42",
                                mimeType = "image/png",
                                fileName = "photo.png",
                                sizeBytes = 1024,
                            )
                        ),
                    )
                ),
            ),
            StepNode(id = "n", type = "device.notify", name = "Tell me", x = 40f, y = 240f),
        ),
        streams = persistentListOf(
            StreamEdge("e1", "t", "h"),
            StreamEdge("e2", "h", "n"),
        ),
    )

    @Test
    fun `full backup preserves parameters, notes, tags, vars and layout`() {
        val bytes = NativeFlowBundle.export(sample()) { setOf("authToken") }
        assertTrue(NativeFlowBundle.isNative(bytes))
        val preview = NativeFlowBundle.preview(bytes, known)

        val restored = preview.flow
        assertEquals("Morning digest", restored.name)
        assertEquals(listOf("daily", "personal"), restored.tags.toList())
        assertEquals("Bhubaneswar", restored.vars["city"])
        val fetch = restored.steps.first { it.id.isNotBlank() && it.type == "net.http" }
        assertEquals("https://example.com/api", fetch.paramString("url"))
        assertEquals("GET", fetch.paramString("method"))
        assertEquals("Review the URL before sharing", fetch.notes)
        assertEquals(40f, fetch.x)
        assertEquals(120f, fetch.y)
        assertEquals(true, fetch.frozenOutput?.first()?.json?.get("ok"))
        assertTrue("unexpected unsupported types: ${preview.unsupportedTypes}", preview.unsupportedTypes.isEmpty())
    }

    @Test
    fun `credentials are stripped, reported for rebind, and never restored`() {
        val bytes = NativeFlowBundle.export(sample()) { setOf("authToken") }
        val text = String(bytes, Charsets.UTF_8)
        assertFalse("a secret leaked into the backup", text.contains("SECRET-123"))
        assertFalse("a nested secret leaked into the backup", text.contains("ALSO-SECRET"))
        assertFalse("a secret variable leaked into the backup", text.contains("DO-NOT-SHIP"))

        val preview = NativeFlowBundle.preview(bytes, known)
        assertTrue(preview.rebind.any { it.contains("Fetch") })
        assertTrue(preview.redactedCount >= 2)
        val fetch = preview.flow.steps.first { it.type == "net.http" }
        assertEquals("", fetch.paramString("authToken"))
        assertEquals("", preview.flow.vars["apiToken"]?.toString())
    }

    @Test
    fun `import remaps every id, disables sparks and drops binary handles`() {
        val original = sample()
        val preview = NativeFlowBundle.preview(NativeFlowBundle.export(original), known)
        val restored = preview.flow

        assertNotEquals(original.id, restored.id)
        assertEquals(original.steps.size, restored.steps.size)
        assertEquals(original.streams.size, restored.streams.size)
        val oldIds = original.steps.map { it.id }.toSet()
        assertTrue(restored.steps.none { it.id in oldIds })
        assertTrue(restored.streams.none { it.id in setOf("e1", "e2") })
        assertTrue(restored.triggers.all { it.disabled })
        assertFalse(restored.steps.first { it.type == "device.notify" }.disabled)

        val newIds = restored.steps.map { it.id }.toSet()
        assertTrue(restored.streams.all { it.fromStepId in newIds && it.toStepId in newIds })
        assertEquals(0L, restored.createdAt)
        assertFalse(restored.isFavorite)

        val frozen = restored.steps.first { it.type == "net.http" }.frozenOutput
        assertNull("a local content URI was imported", frozen?.first()?.binaryRef("photo"))
    }

    @Test
    fun `unknown step types are reported but do not crash the import`() {
        val withUnknown = sample().let { f ->
            val steps = f.steps.toMutableList().apply { add(StepNode("x", "saas.mystery", "Mystery")) }
            f.copy(
                steps = steps.toImmutableList(),
                streams = (f.streams + StreamEdge("e4", "n", "x")).toImmutableList(),
            )
        }
        val preview = NativeFlowBundle.preview(NativeFlowBundle.export(withUnknown), known)
        assertEquals(listOf("saas.mystery"), preview.unsupportedTypes)
    }

    @Test
    fun `cycles, wrong format and oversized files are rejected`() {
        val cyclic = sample().copy(
            streams = persistentListOf(
                StreamEdge("e1", "t", "h"),
                StreamEdge("e2", "h", "n"),
                StreamEdge("e3", "n", "h"),
            )
        )
        assertThrowsInvalid { NativeFlowBundle.export(cyclic) }
        assertThrowsInvalid { NativeFlowBundle.preview(ByteArray(0), known) }
        assertThrowsInvalid { NativeFlowBundle.preview("not json".toByteArray(), known) }
        assertThrowsInvalid {
            NativeFlowBundle.preview(ByteArray(NativeFlowBundle.MAX_BYTES + 1), known)
        }
        val structure = FlowStructureBundle.export(sample())
        assertFalse(NativeFlowBundle.isNative(structure))
        assertThrowsInvalid { NativeFlowBundle.preview(structure, known) }
    }

    @Test
    fun `a tampered backup cannot smuggle a credential back in`() {
        val bytes = NativeFlowBundle.export(sample()) { setOf("authToken") }
        val tampered = String(bytes, Charsets.UTF_8).replace("\"authToken\":\"\"", "\"authToken\":\"SMUGGLED\"")
        val preview = NativeFlowBundle.preview(tampered.toByteArray(), known)
        assertEquals("", preview.flow.steps.first { it.type == "net.http" }.paramString("authToken"))
    }

    private fun assertThrowsInvalid(block: () -> Unit) {
        try {
            block()
            assertTrue("expected an InvalidBundle", false)
        } catch (e: NativeFlowBundle.InvalidBundle) {
            assertTrue(e.message?.isNotBlank() == true)
        }
    }

}
