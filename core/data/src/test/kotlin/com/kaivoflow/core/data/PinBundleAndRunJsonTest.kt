package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.PinSource
import com.kaivoflow.core.engine.stepdata.PinnedData
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinBundleAndRunJsonTest {
    private val known = setOf("spark.manual", "net.http")
    private val flow = Flow(
        id = "flow-1", name = "Pins",
        steps = persistentListOf(StepNode(id = "t", type = "spark.manual", name = "Start"), StepNode(id = "h", type = "net.http", name = "Fetch", y = 100f)),
        streams = persistentListOf(StreamEdge("e1", "t", "h")),
    )
    private val pin = PinnedData(
        listOf(listOf(
            Item(json = mapOf("a" to 1.0, "apiToken" to "SECRET-PIN", "n" to mapOf("x" to listOf("y"))),
                binary = mapOf("p" to BinaryDataRef("content://m/1", "image/png", "p.png", 5))),
            Item.of("b" to "two"),
        ), emptyList()),
        bytes = 99, pinnedAt = 1234L, source = PinSource.Execution,
    )

    @Test fun pinsRoundTripThroughTheBundleWithNewStepIds() {
        val bytes = NativeFlowBundle.export(flow, pins = mapOf("h" to pin, "ghost" to pin))
        val text = String(bytes)
        assertFalse("secret-looking keys in pins are blanked", text.contains("SECRET-PIN"))
        val root = Json.parseToJsonElement(text) as JsonObject
        assertEquals(setOf("h"), (root["pins"] as JsonObject).keys)
        val prev = NativeFlowBundle.preview(bytes, known)
        val newH = prev.flow.steps.single { it.name == "Fetch" }.id
        assertTrue(newH != "h")
        val got = prev.pins.getValue(newH)
        assertEquals(setOf(newH), prev.pins.keys)
        assertEquals(2, got.ports[0].size); assertTrue(got.ports[1].isEmpty())
        assertEquals(1.0, got.ports[0][0].json["a"]); assertEquals("", got.ports[0][0].json["apiToken"])
        assertTrue("binary never travels", got.ports[0][0].binary.isEmpty())
        assertEquals(PinSource.Execution, got.source); assertEquals(1234L, got.pinnedAt)
        assertEquals(0, prev.droppedPins)
    }

    @Test fun legacyBundlesWithoutPinsStillImport() {
        val bytes = NativeFlowBundle.export(flow)
        assertFalse(String(bytes).contains("\"pins\""))
        val prev = NativeFlowBundle.preview(bytes, known)
        assertTrue(prev.pins.isEmpty()); assertEquals(0, prev.droppedPins); assertEquals(2, prev.flow.steps.size)
    }

    @Test fun importDropsUnknownStepsUnreadablePinsAndOversizedOnes() {
        val good = String(NativeFlowBundle.export(flow, pins = mapOf("h" to pin)))
        val root = Json.parseToJsonElement(good) as JsonObject
        val pins = root["pins"] as JsonObject
        val h = pins["h"]!!
        val huge = PinCodec.toBundleJson(PinnedData(listOf(listOf(Item.of("v" to "x".repeat(1_200_000)))), 1, 1, PinSource.Json))
        val tampered = JsonObject(root + ("pins" to JsonObject(mapOf("h" to h, "ghost" to h, "t" to huge, "bad" to JsonArray(emptyList())))))
        val prev = NativeFlowBundle.preview(tampered.toString().toByteArray(), known)
        assertEquals(1, prev.pins.size)
        assertEquals(3, prev.droppedPins) // ghost (unknown), t (over the 1 MB step cap), bad (unreadable)
    }

    // ---- slim runJson with compat reader ----

    private fun run(output: List<Item>) = RunRecord(
        id = "r1", flowId = "f", flowName = "F", status = RunStatus.Succeeded, startedAt = 1, finishedAt = 9,
        stepRuns = persistentListOf(
            StepRun("a", "A", StepRunStatus.Succeeded, 1, 2, itemsIn = 2, itemsOut = 3, output = output.toImmutableList(),
                portCounts = persistentMapOf(0 to 2, 1 to 1), itemsFailed = 1, runIndex = 0),
        ),
    )

    @Test fun newRunDocumentsCarryCountsButNoItems() {
        val text = JsonCodec.encodeRun(run(listOf(Item.of("k" to "v"))))
        assertFalse(text.contains("\"output\""))
        assertFalse(text.contains("\"k\""))
        val back = JsonCodec.decodeRun(text)!!.stepRuns.single()
        assertTrue(back.output.isEmpty())
        assertEquals(mapOf(0 to 2, 1 to 1), back.portCounts.toMap())
        assertEquals(1, back.itemsFailed); assertEquals(3, back.itemsOut); assertEquals(StepRunStatus.Succeeded, back.status)
    }

    @Test fun oldRunDocumentsKeepTheirOutputWhenRead() {
        val old = """{"id":"r0","flowId":"f","flowName":"F","status":"Succeeded","startedAt":1,"finishedAt":9,"trigger":"Manual","error":null,
            "steps":[{"stepId":"a","name":"A","status":"Succeeded","startedAt":1,"finishedAt":2,"itemsIn":1,"itemsOut":1,"error":null,"attempts":1,
            "output":[{"json":{"k":"v"}}]}]}"""
        val back = JsonCodec.decodeRun(old)!!
        assertEquals("r0", back.id)
        val s = back.stepRuns.single()
        assertEquals("v", s.output.single().json["k"])
        assertTrue(s.portCounts.isEmpty()); assertEquals(0, s.itemsFailed)
        assertNull(back.error)
    }
}
