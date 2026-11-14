package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.engine.IssueSeverity
import kotlinx.collections.immutable.persistentListOf

/**
 * Phone-native Sparks.
 *
 * The executors stay pass-through: the operating system owns the interesting work (proximity
 * alerts, connectivity broadcasts, boot), and `:core:work` turns each OS event into a seeded Run.
 * These specs and [EventTriggerMatcher] are the single source of truth for both sides.
 */
class GeofenceTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.GEOFENCE,
        title = "When I arrive somewhere",
        category = StepCategory.Trigger,
        subtitle = "React when the phone enters or leaves a place",
        iconKey = "location",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("latitude", "Latitude", ParamType.Number, required = true, default = 20.2961),
            ParamSpec("longitude", "Longitude", ParamType.Number, required = true, default = 85.8245),
            ParamSpec("radiusMeters", "Radius (metres)", ParamType.Number, default = 150),
            ParamSpec(
                key = "transition",
                label = "When",
                type = ParamType.Select,
                default = "enter",
                options = persistentListOf(
                    ParamOption("enter", "Arriving"),
                    ParamOption("exit", "Leaving"),
                    ParamOption("both", "Both"),
                ),
            ),
        ),
        keywords = persistentListOf("geofence", "location", "arrive", "leave", "place", "gps", "home", "office"),
        requiresSensitivePermission = "android.permission.ACCESS_FINE_LOCATION",
    )

    override fun validate(step: StepNode, spec: NodeSpec): List<ValidationIssue> {
        val lat = step.paramNumber("latitude", 0.0)
        val lon = step.paramNumber("longitude", 0.0)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return listOf(
                ValidationIssue(
                    "bad_geofence",
                    "Latitude must be between -90 and 90, longitude between -180 and 180.",
                    IssueSeverity.Blocking,
                    step.id,
                )
            )
        }
        return emptyList()
    }
}

class BluetoothTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.BLUETOOTH,
        title = "When a Bluetooth device connects",
        category = StepCategory.Trigger,
        subtitle = "React to headphones, car kits and watches",
        iconKey = "bluetooth",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("deviceNameContains", "Device name contains", ParamType.Text, placeholder = "Car, Buds"),
            ParamSpec("deviceAddress", "Exact device address", ParamType.Text, advanced = true, placeholder = "AA:BB:CC:DD:EE:FF"),
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "connected",
                options = persistentListOf(
                    ParamOption("connected", "Connects"),
                    ParamOption("disconnected", "Disconnects"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("bluetooth", "headphones", "car", "connect", "device", "watch"),
        requiresSensitivePermission = "android.permission.BLUETOOTH_CONNECT",
    )
}

class WifiTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.WIFI,
        title = "When Wi-Fi changes",
        category = StepCategory.Trigger,
        subtitle = "React when you join or leave a network",
        iconKey = "wifi",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("ssidContains", "Network name contains", ParamType.Text, placeholder = "Home, Office"),
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "connected",
                options = persistentListOf(
                    ParamOption("connected", "Connected"),
                    ParamOption("disconnected", "Disconnected"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("wifi", "network", "ssid", "connect", "home", "internet"),
    )
}

class HeadsetTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.HEADSET,
        title = "When headphones are plugged",
        category = StepCategory.Trigger,
        subtitle = "React to a wired headset being connected or removed",
        iconKey = "headset",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "plugged",
                options = persistentListOf(
                    ParamOption("plugged", "Plugged in"),
                    ParamOption("unplugged", "Unplugged"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("headset", "headphones", "jack", "audio", "plug"),
    )
}

class BootTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.BOOT,
        title = "After the phone restarts",
        category = StepCategory.Trigger,
        subtitle = "Run once when the device boots",
        iconKey = "power",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("delaySeconds", "Wait after boot (seconds)", ParamType.Number, default = 20, advanced = true),
        ),
        keywords = persistentListOf("boot", "restart", "startup", "reboot", "after restart"),
    )
}

/**
 * A light validation helper shared by Sparks with numeric coordinates/intervals. Kept here so the
 * core:nodes module stays free of Android and can be unit-tested on the JVM.
 */
internal object MobileTriggerMath {
    /** Great-circle distance in metres, used to double-check a proximity event. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6_371_000.0
        fun radians(d: Double) = d * Math.PI / 180.0
        val dLat = radians(lat2 - lat1)
        val dLon = radians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(radians(lat1)) * Math.cos(radians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * radius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
