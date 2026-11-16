package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
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
            ParamSpec(
                key = "dwellSeconds",
                label = "Stay for (seconds)",
                type = ParamType.Number,
                default = 0,
                advanced = true,
                hint = "0 fires on arrival. A number fires only after the phone stays inside this long (up to 24 hours). Not used when leaving.",
                showWhen = mapOf("transition" to listOf("enter", "both")),
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
        val dwell = step.paramNumber("dwellSeconds", 0.0)
        if (!dwell.isFinite() || dwell < 0 || dwell > com.kaivoflow.core.engine.alwayson.GeofenceConstants.MAX_DWELL_SEC) {
            return listOf(
                ValidationIssue(
                    "bad_geofence_dwell",
                    "Stay for must be between 0 and 86400 seconds (24 hours).",
                    IssueSeverity.Blocking,
                    step.id,
                )
            )
        }
        if (dwell > 0 && step.paramString("transition", "enter") == "exit") {
            return listOf(
                ValidationIssue(
                    "geofence_dwell_ignored",
                    "Stay for is not used when the Spark fires on leaving; it will be ignored.",
                    IssueSeverity.Warning,
                    step.id,
                )
            )
        }
        return emptyList()
    }
}
