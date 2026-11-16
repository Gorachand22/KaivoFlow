package com.kaivoflow.core.data

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.*
import org.junit.Test

class FlowStructureBundleTest {
    private val known = setOf("spark.manual", "net.http")
    private fun sample() = Flow(
        id = "original-flow", name = "Test flow", vars = persistentMapOf("token" to "VAR_SECRET"),
        tags = persistentListOf("PRIVATE_TAG"), settings = FlowSettings(errorStepId = "action"),
        steps = persistentListOf(
            StepNode("start", "spark.manual", "Start", params = persistentMapOf("secret" to "SECRET_PARAM")),
            StepNode("action", "net.http", "Call API", params = persistentMapOf("headers" to mapOf("Authorization" to "Bearer SECRET_HEADER"), "url" to "https://SECRET_URL"),
                notes = "SECRET_NOTE", frozenOutput = persistentListOf(FlowData(mapOf("payload" to "SECRET_OUTPUT"))), x = 5f, y = 9f),
        ), streams = persistentListOf(StreamEdge("edge", "start", "action", label = "ok")),
    )
    private fun import(bytes: ByteArray) = FlowStructureBundle.preview(bytes, known)
    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected InvalidBundle") } catch (_: FlowStructureBundle.InvalidBundle) { }
    }

    @Test fun structureExportExcludesSettingsCredentialsAndData() {
        val text = FlowStructureBundle.export(sample()).toString(Charsets.UTF_8)
        for (secret in listOf("VAR_SECRET", "PRIVATE_TAG", "SECRET_PARAM", "SECRET_HEADER", "SECRET_URL", "SECRET_NOTE", "SECRET_OUTPUT")) {
            assertFalse("Found $secret", text.contains(secret))
        }
        assertTrue(text.contains("Call API"))
        assertTrue(text.contains("kaivoflow.structure"))
    }

    @Test fun freshIdsAndEdgesPreserveGraphButDisableAllSteps() {
        val copy = import(FlowStructureBundle.export(sample())).flow
        assertNotEquals("original-flow", copy.id)
        assertEquals(2, copy.steps.size)
        assertTrue(copy.steps.all { it.disabled && it.params.isEmpty() && it.notes.isEmpty() && it.frozenOutput == null })
        assertTrue(copy.vars.isEmpty())
        assertEquals(copy.steps[0].id, copy.streams.single().fromStepId)
        assertEquals(copy.steps[1].id, copy.streams.single().toStepId)
        assertNotEquals("start", copy.steps[0].id)
        assertEquals(copy.steps[1].id, copy.settings.errorStepId)
    }

    @Test fun separateImportsNeverReuseIds() {
        val bytes = FlowStructureBundle.export(sample())
        val a = import(bytes).flow; val b = import(bytes).flow
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.steps[0].id, b.steps[0].id)
        assertNotEquals(a.streams[0].id, b.streams[0].id)
    }

    @Test fun unsupportedTypesAreReportedAndDisabled() {
        val flow = sample().copy(steps = persistentListOf(sample().steps[0], sample().steps[1].copy(type = "future.unknown")))
        val preview = import(FlowStructureBundle.export(flow))
        assertEquals(listOf("future.unknown"), preview.unsupportedTypes)
        assertTrue(preview.flow.steps[1].disabled)
    }

    @Test fun modifiedInputsCannotSmuggleParamsOrTurnStepsOn() {
        val json = FlowStructureBundle.export(sample()).toString(Charsets.UTF_8)
            .replace("\"params\":{}", "\"params\":{\"secret\":\"INJECTED\"}")
            .replace("\"disabled\":true", "\"disabled\":false")
        val copy = import(json.toByteArray()).flow
        assertTrue(copy.steps.all { it.disabled && it.params.isEmpty() })
    }

    @Test fun invalidVersionAndForeignFormatsAreRejected() {
        val data = FlowStructureBundle.export(sample()).toString(Charsets.UTF_8)
        fails { import(data.replace("\"version\":1", "\"version\":2").toByteArray()) }
        fails { import("{\"nodes\":[],\"connections\":{}}".toByteArray()) }
        fails { import("not json".toByteArray()) }
    }

    @Test fun rejectsLargeDeepAndInvalidUtf8Inputs() {
        fails { import(ByteArray(FlowStructureBundle.MAX_BYTES + 1) { 32 }) }
        fails { import(byteArrayOf(0xc3.toByte(), 0x28)) }
        fails { import(("[".repeat(50) + "]".repeat(50)).toByteArray()) }
    }

    @Test fun repeatedIdsAndDanglingEndpointsAreRejected() {
        val f = sample()
        fails { FlowStructureBundle.export(f.copy(steps = persistentListOf(f.steps[0], f.steps[1].copy(id = "start")))) }
        fails { FlowStructureBundle.export(f.copy(streams = persistentListOf(StreamEdge("e", "start", "missing")))) }
        fails { FlowStructureBundle.export(f.copy(settings = FlowSettings(errorStepId = "missing"))) }
    }

    @Test fun cyclesAndInvalidPortsAreRejected() {
        val f = sample()
        fails { FlowStructureBundle.export(f.copy(streams = persistentListOf(StreamEdge("e", "start", "action"), StreamEdge("e2", "action", "start")))) }
        fails { FlowStructureBundle.export(f.copy(streams = persistentListOf(StreamEdge("e", "start", "action", fromPort = -1)))) }
    }

    @Test fun missingOrMalformedElementsCannotBeSilentlyDropped() {
        val valid = FlowStructureBundle.export(sample()).toString(Charsets.UTF_8)
        fails { import(valid.replace("\"steps\":[", "\"steps\":[null,").toByteArray()) }
        fails { import(valid.replace("\"streams\":[", "\"streams\":[7,").toByteArray()) }
    }

    @Test fun tamperedInputWithDuplicateIdsIsRejectedAtImport() {
        val valid = FlowStructureBundle.export(sample()).toString(Charsets.UTF_8)
        val duplicate = valid.replace("\"id\":\"action\"", "\"id\":\"start\"")
        assertNotEquals(valid, duplicate)
        fails { import(duplicate.toByteArray()) }
    }

    @Test fun originalModelIsNotModifiedByExport() {
        val f = sample()
        FlowStructureBundle.export(f)
        assertEquals("SECRET_NOTE", f.steps[1].notes)
        assertFalse(f.steps[0].disabled)
        assertNotNull(f.steps[1].frozenOutput)
        assertEquals("VAR_SECRET", f.vars["token"])
    }

    @Test fun bracketsAndEscapedQuotesInNamesAreNotTreatedAsNesting() {
        val f = sample().copy(name = "A [complex] \\\"name\\\"")
        assertEquals(f.name, import(FlowStructureBundle.export(f)).flow.name)
    }
}
