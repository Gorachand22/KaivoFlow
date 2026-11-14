package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

/** The rest of the phone capabilities exposed as small, single-purpose Steps. */
class ClipboardReadNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.clipboard_read",
        title = "Read clipboard",
        category = StepCategory.Device,
        subtitle = "Use the text currently copied on this phone",
        iconKey = "clipboard",
        params = persistentListOf(
            textParam("outputField", "Save text in", default = "clipboard"),
        ),
        keywords = persistentListOf("clipboard", "paste", "copied", "text", "read"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val text = gateway.readClipboard().orEmpty()
        val field = ctx.outputName("clipboard")
        return ctx.output(listOf(FlowData(mapOf(field to text, "empty" to text.isBlank()))))
    }
}

class ToastNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.toast",
        title = "Quick message",
        category = StepCategory.Device,
        subtitle = "Flash a short on-screen message",
        iconKey = "info",
        params = persistentListOf(
            textParam("text", "Message", default = "{{ \$json.message }}", required = true),
        ),
        keywords = persistentListOf("toast", "message", "popup", "flash", "notice"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, _ ->
            gateway.showToast(ctx.resolveText(params["text"], index).take(300))
        }
        return ctx.output(sources)
    }
}

class ShareTextNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.share",
        title = "Share sheet",
        category = StepCategory.Device,
        subtitle = "Hand text to another app on this phone",
        iconKey = "share",
        params = persistentListOf(
            textParam("text", "Text to share", default = "{{ \$json.text }}", required = true),
            textParam("title", "Share title", default = "Shared from KaivoFlow"),
        ),
        keywords = persistentListOf("share", "send", "sheet", "another app", "text"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, _ ->
            gateway.shareText(
                ctx.resolveText(params["text"], index),
                ctx.resolveText(params["title"], index, "Shared from KaivoFlow"),
            )
        }
        return ctx.output(sources)
    }
}

class TorchNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.torch",
        title = "Torch",
        category = StepCategory.Device,
        subtitle = "Turn the camera light on or off",
        iconKey = "flashOn",
        requiresSensitivePermission = "android.permission.CAMERA",
        params = persistentListOf(
            ParamSpec(
                key = "state",
                label = "Torch",
                type = ParamType.Select,
                default = "on",
                options = selectOptions("on" to "Turn on", "off" to "Turn off", "toggle" to "Toggle"),
            ),
        ),
        keywords = persistentListOf("torch", "flashlight", "camera light", "light", "hardware"),
    )

    private var lastOn = false

    override suspend fun execute(ctx: StepContext): StepResult {
        val state = Values.asString(ctx.step.params["state"], "on")
        val on = when (state) {
            "off" -> false
            "toggle" -> !lastOn
            else -> true
        }
        if (!gateway.setTorch(on)) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "this phone could not control the torch")
        }
        lastOn = on
        return ctx.output(ctx.input.ifEmpty { listOf(FlowData(mapOf("torch" to on))) })
    }
}

class VolumeNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.volume",
        title = "Volume",
        category = StepCategory.Device,
        subtitle = "Set the media volume to a percentage",
        iconKey = "speaker",
        params = persistentListOf(
            ParamSpec("level", "Volume (0–100)", ParamType.Number, default = 50),
        ),
        keywords = persistentListOf("volume", "sound", "audio", "media", "loudness"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val level = (Values.asDouble(ctx.step.params["level"]) ?: 50.0).toInt().coerceIn(0, 100)
        if (!gateway.setVolume(level)) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "this phone could not change the volume")
        }
        return ctx.output(ctx.input.ifEmpty { listOf(FlowData(mapOf("volume" to level.toDouble()))) })
    }
}

class LocationNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.location",
        title = "Current location",
        category = StepCategory.Device,
        subtitle = "Read the phone's last known coordinates",
        iconKey = "location",
        requiresSensitivePermission = "android.permission.ACCESS_FINE_LOCATION",
        params = persistentListOf(
            textParam("outputField", "Save location in", default = "location"),
        ),
        keywords = persistentListOf("location", "gps", "coordinates", "latitude", "longitude", "where am i"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val location = gateway.getLocation()
        if (location.isEmpty() || location["available"] == false) {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                Values.asString(location["reason"], "no location is available; allow location access and try again"),
            )
        }
        val field = ctx.outputName("location")
        return ctx.output(listOf(FlowData(mapOf(field to location))))
    }
}

class WifiInfoNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.wifi_info",
        title = "Wi-Fi details",
        category = StepCategory.Device,
        subtitle = "Read the current network name and connection state",
        iconKey = "wifi",
        params = persistentListOf(
            textParam("outputField", "Save details in", default = "wifi"),
        ),
        keywords = persistentListOf("wifi", "network", "ssid", "connection", "signal", "details"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val info = gateway.getWifiInfo()
        val field = ctx.outputName("wifi")
        return ctx.output(listOf(FlowData(mapOf(field to info, "available" to info.isNotEmpty()))))
    }
}

class ContactLookupNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.contact",
        title = "Find a contact",
        category = StepCategory.Device,
        subtitle = "Look up a saved contact by name or number",
        iconKey = "contacts",
        requiresSensitivePermission = "android.permission.READ_CONTACTS",
        params = persistentListOf(
            textParam("query", "Name or number", default = "{{ \$json.query }}", required = true),
            textParam("outputField", "Save contact in", default = "contact"),
        ),
        keywords = persistentListOf("contact", "address book", "phone number", "lookup", "person"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.outputName("contact")
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val query = ctx.resolveText(params["query"], index)
            val contact = gateway.lookupContact(query)
            item.copy(json = item.json + mapOf(field to contact, "found" to (contact["found"] == true)))
        })
    }
}

class CalendarEventNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.calendar_event",
        title = "Add calendar event",
        category = StepCategory.Device,
        subtitle = "Put a reminder into the phone's calendar",
        iconKey = "eventNote",
        requiresSensitivePermission = "android.permission.WRITE_CALENDAR",
        params = persistentListOf(
            textParam("title", "Title", default = "{{ \$json.title }}", required = true),
            textParam("start", "Starts at (epoch ms or ISO)", default = "{{ \$json.start }}", required = true),
            ParamSpec("durationMinutes", "Lasts (minutes)", ParamType.Number, default = 30),
            textParam("description", "Notes", placeholder = "{{ \$json.notes }}"),
            textParam("outputField", "Save result in", default = "event"),
        ),
        keywords = persistentListOf("calendar", "event", "meeting", "reminder", "schedule", "appointment"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.outputName("event")
        val duration = (Values.asDouble(params["durationMinutes"]) ?: 30.0).toLong().coerceIn(1, 60 * 24 * 30)
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val start = parseMillis(ctx, ctx.resolveText(params["start"], index))
            val id = gateway.addCalendarEvent(
                title = ctx.resolveText(params["title"], index, "KaivoFlow event"),
                startMillis = start,
                endMillis = start + duration * 60_000L,
                description = ctx.resolveText(params["description"], index),
            ) ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the calendar could not save this event")
            item.copy(json = item.json + (field to mapOf(
                "id" to id,
                "start" to start.toDouble(),
                "end" to (start + duration * 60_000L).toDouble(),
            )))
        })
    }

    private fun parseMillis(ctx: StepContext, text: String): Long {
        val trimmed = text.trim()
        trimmed.toLongOrNull()?.let { return it }
        runCatching { return java.time.Instant.parse(trimmed).toEpochMilli() }
        runCatching {
            return java.time.LocalDateTime.parse(trimmed.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$trimmed\" is not a date-time this Step understands")
    }
}
