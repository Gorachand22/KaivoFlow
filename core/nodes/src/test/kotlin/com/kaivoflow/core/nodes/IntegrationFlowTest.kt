package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.RunOptions
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end: real catalog, real runner, fake network and fake device.
 * This is the closest thing to "does the app actually work" that runs without a phone.
 */
class IntegrationFlowTest {

    private fun step(
        id: String,
        type: String,
        name: String,
        y: Float,
        params: Map<String, Any?> = emptyMap(),
        onError: OnErrorPolicy = OnErrorPolicy.Stop,
        frozen: List<FlowData>? = null,
    ) = StepNode(
        id = id,
        type = type,
        name = name,
        params = params.toImmutableMap(),
        y = y,
        onError = onError,
        frozenOutput = frozen?.toImmutableList(),
    )

    private fun runner(http: RecordingHttp, devices: RecordingDevices) = FlowRunner(
        registry = NodeCatalog.registry(devices),
        evaluator = TestEvaluator(),
        http = http,
        credentials = TestCredentials(),
        clock = { 1_700_000_000_000 },
    )

    @Test
    fun `morning weather digest runs end to end and notifies the phone`() = runBlocking {
        val http = RecordingHttp()
        http.responder = {
            HttpResponseSpec(
                status = 200,
                body = mapOf(
                    "current" to mapOf("temperature_2m" to 29.5, "relative_humidity_2m" to 65, "weather_code" to 3),
                    "daily" to mapOf(
                        "temperature_2m_max" to listOf(33.0),
                        "temperature_2m_min" to listOf(22.0),
                        "precipitation_probability_max" to listOf(20),
                    ),
                ),
            )
        }
        val devices = RecordingDevices()

        val flow = Flow(
            id = "morning",
            name = "Morning digest",
            steps = persistentListOf(
                step("t", "trigger.schedule", "Every morning", 0f),
                step("w", "saas.weather", "Weather", 100f, mapOf("city" to "Bhubaneswar")),
                step(
                    "txt", "transform.template", "Write the digest", 200f,
                    mapOf(
                        "template" to "{{ \$json.city }}: {{ \$json.condition }}, {{ \$json.temperature }}°C (low {{ \$json.min }} / high {{ \$json.max }})",
                        "outputField" to "digest",
                    ),
                ),
                step(
                    "n", "device.notify", "Tell me", 300f,
                    mapOf("title" to "Good morning", "body" to "{{ \$json.digest }}", "channel" to "default"),
                ),
            ),
            streams = persistentListOf(
                StreamEdge("s1", "t", "w"),
                StreamEdge("s2", "w", "txt"),
                StreamEdge("s3", "txt", "n"),
            ),
            settings = FlowSettings(timezone = "Asia/Kolkata"),
        )

        val events = mutableListOf<RunEvent>()
        val record: RunRecord = runner(http, devices).run(
            flow,
            RunTrigger(kind = "schedule", items = persistentListOf(FlowData()).toImmutableList()),
            onEvent = { events += it },
        )

        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(4, record.stepRuns.size)
        assertEquals(1, devices.notifications.size)
        assertEquals("Good morning", devices.notifications[0].first)
        assertTrue(
            "digest should contain the weather: ${devices.notifications[0].second}",
            devices.notifications[0].second.contains("Bhubaneswar") && devices.notifications[0].second.contains("Overcast"),
        )
        // The Golden Current animation is driven by these events.
        assertTrue(events.count { it is RunEvent.StepFinished } == 4)
        assertTrue(events.last() is RunEvent.RunFinished)
    }

    @Test
    fun `ai failure is routed to the error handler and still notifies`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 500, bodyText = "upstream exploded") }
        val devices = RecordingDevices()

        val flow = Flow(
            id = "ai-error",
            name = "AI with a safety net",
            steps = persistentListOf(
                step("t", "trigger.manual", "Start", 0f),
                step(
                    "ai", "ai.chat", "Ask AI", 100f,
                    mapOf("credential" to "cred-1", "model" to "gpt-4o-mini", "prompt" to "hi"),
                ),
                step(
                    "handler", "device.notify", "Alert me", 0f,
                    mapOf("title" to "Automation failed", "body" to "{{ \$json.error }}"),
                ),
            ),
            streams = persistentListOf(StreamEdge("s1", "t", "ai")),
            settings = FlowSettings(errorStepId = "handler"),
        )

        val record = runner(http, devices).run(flow, RunTrigger(items = persistentListOf(FlowData()).toImmutableList()))

        assertEquals(RunStatus.Failed, record.status)
        assertEquals(StepRunStatus.Failed, record.stepRuns.first { it.stepId == "ai" }.status)
        assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "handler" }.status)
        assertTrue(devices.notifications.single().second.contains("500"))
    }

    @Test
    fun `frozen output lets a flow be rebuilt without calling the network`() = runBlocking {
        val http = RecordingHttp()
        val devices = RecordingDevices()
        val pinned = listOf(FlowData(mapOf("city" to "Pinned City", "condition" to "Clear sky", "temperature" to 31)))

        val flow = Flow(
            id = "frozen",
            name = "Frozen demo",
            steps = persistentListOf(
                step("t", "trigger.manual", "Start", 0f),
                step("w", "saas.weather", "Weather", 100f, mapOf("city" to "Nowhere"), frozen = pinned),
                step("n", "device.notify", "Tell me", 200f, mapOf("title" to "Weather", "body" to "{{ \$json.city }}")),
            ),
            streams = persistentListOf(StreamEdge("s1", "t", "w"), StreamEdge("s2", "w", "n")),
        )

        val record = runner(http, devices).run(flow, RunTrigger(items = persistentListOf(FlowData()).toImmutableList()))

        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(StepRunStatus.Frozen, record.stepRuns.first { it.stepId == "w" }.status)
        assertEquals(0, http.requests.size) // no network call at all
        assertEquals("Pinned City", devices.notifications.single().second)
    }

    @Test
    fun `testing a single step does not run the rest of the flow`() = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("current" to mapOf("weather_code" to 0), "daily" to mapOf())) }
        val devices = RecordingDevices()
        val flow = Flow(
            id = "single",
            name = "Single",
            steps = persistentListOf(
                step("t", "trigger.manual", "Start", 0f),
                step("w", "saas.weather", "Weather", 100f, mapOf("city" to "Bhubaneswar")),
                step("n", "device.notify", "Tell me", 200f, mapOf("title" to "x", "body" to "y")),
            ),
            streams = persistentListOf(StreamEdge("s1", "t", "w"), StreamEdge("s2", "w", "n")),
        )

        val run = runner(http, devices).testStep(flow, "w", listOf(FlowData()))
        assertEquals(StepRunStatus.Succeeded, run.status)
        assertTrue(run.output.isNotEmpty())
        assertTrue(devices.notifications.isEmpty())
    }
}
