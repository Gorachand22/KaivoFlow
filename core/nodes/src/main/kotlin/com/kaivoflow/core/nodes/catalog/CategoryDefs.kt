package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.model.AliasMap
import com.kaivoflow.core.model.AliasTarget
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepNormalizer
import kotlinx.collections.immutable.toImmutableList

/** Taxonomy placement of one Step: its path, cross-listings and lifecycle. Metadata only. */
data class Placement(
    val path: String,
    val alsoIn: List<String> = emptyList(),
    val lifecycle: Lifecycle = Lifecycle.Stable,
)

/**
 * New category path for every registered Step (taxonomy spec section 3, "Cat" column). Keyed by
 * the CURRENT id, so no id is renamed and no stored data changes. Where the verdict table has no
 * category (removed Steps) the placement rule of section 2 decides.
 */
object CategoryDefs {

    private fun p(path: String, alsoIn: String? = null, lifecycle: Lifecycle = Lifecycle.Stable) =
        Placement(path, listOfNotNull(alsoIn), lifecycle)

    val placements: Map<String, Placement> = mapOf(
        // 3.1 Sparks
        "spark.manual" to p("spark/start"),
        "spark.schedule" to p("spark/start"),
        "spark.webhook" to p("spark/incoming"),
        "spark.share" to p("spark/incoming"),
        "spark.notification" to p("spark/device_events", "device/notifications"),
        "spark.sms" to p("spark/device_events", "device/telephony"),
        "spark.battery" to p("spark/device_events", "device/sensors"),
        "spark.geofence" to p("spark/device_events", "device/location"),
        "spark.bluetooth" to p("spark/device_events", "device/connectivity"),
        "spark.wifi" to p("spark/device_events", "device/connectivity"),
        "spark.headset" to p("spark/device_events", "device/io"),
        "spark.boot" to p("spark/device_events", "device/system"),
        // 3.2 Logic
        "flow.if" to p("flow/branching"),
        "flow.switch" to p("flow/branching"),
        "flow.merge" to p("flow/branching"),
        "flow.loop" to p("flow/looping"),
        "flow.wait" to p("flow/timing"),
        "flow.stop" to p("flow/timing"),
        "flow.on_error" to p("flow/timing"),
        "flow.subflow" to p("flow/composition"),
        "flow.noop" to p("flow/composition"),
        "data.filter" to p("data/items"),
        "data.sort" to p("data/items"),
        "data.limit" to p("data/items"),
        "data.dedupe" to p("data/items"),
        "data.compare" to p("data/items"),
        "logic.table" to p("data/storage", lifecycle = Lifecycle.Hidden),
        // 3.3 Transform
        "data.set" to p("data/items"),
        "data.date" to p("data/time"),
        "data.split_out" to p("data/items"),
        "transform.aggregate" to p("data/items", lifecycle = Lifecycle.Hidden),
        "transform.stats" to p("data/items", lifecycle = Lifecycle.Hidden),
        "data.xml" to p("data/text"),
        "data.html" to p("data/text"),
        "data.markdown" to p("data/text"),
        "data.ical" to p("data/text"),
        "transform.csv" to p("files/convert", lifecycle = Lifecycle.Hidden),
        "transform.binary" to p("files/convert", lifecycle = Lifecycle.Hidden),
        "files.pdf" to p("files/document"),
        "security.crypto" to p("security/crypto"),
        "security.totp" to p("security/auth"),
        "code.javascript" to p("code/script"),
        // 3.4 AI (legacy trio, replaced later)
        "ai.chat" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        "ai.extract" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        "ai.classify" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        // 3.4 AI family (phase 3E): the real Agent and its building blocks
        "ai.agent" to p("ai/agents"),
        "ai.chat_model" to p("ai/models"),
        "ai.basic_chain" to p("ai/chains"),
        "ai.parser.structured" to p("ai/parsers"),
        // 3.5 SaaS
        "net.http" to p("net/web"),
        "net.rss" to p("net/web"),
        "app.telegram" to p("app/messaging"),
        "app.slack" to p("app/messaging"),
        "app.discord" to p("app/messaging"),
        "app.google_sheets" to p("app/productivity"),
        "app.notion" to p("app/productivity"),
        // 3.6 Device
        "device.notify" to p("device/notifications"),
        "device.sms" to p("device/telephony"),
        "device.contact" to p("device/contacts_calendar"),
        "device.calendar_event" to p("device/contacts_calendar"),
        "device.location" to p("device/location"),
        "device.battery_status" to p("device/sensors"),
        "device.network_status" to p("device/connectivity"),
        "device.file" to p("device/files_media"),
        "device.open" to p("device/system"),
        "device.volume" to p("device/system"),
        "device.clipboard" to p("device/io"),
        "device.share" to p("device/io"),
        "device.speak" to p("device/io"),
        "device.vibrate" to p("device/io"),
        "device.torch" to p("device/io"),
    )

    /** Returns [spec] with its placement filled in; a spec that already declares a path is kept. */
    fun place(spec: NodeSpec): NodeSpec {
        if (spec.categoryPath.isNotEmpty()) return spec
        val placement = placements[spec.id] ?: return spec
        return spec.copy(
            categoryPath = placement.path,
            alsoIn = placement.alsoIn.toImmutableList(),
            lifecycle = placement.lifecycle,
        )
    }
}

/**
 * Aliases and converters for saved Flows (taxonomy spec section 6). [renames] is the one-time id
 * rename of step 5.7.6 (old id to `<category>.<name>`); entries are append-only and never reused.
 * Merged and removed Steps are added by their own verdict change.
 */
object CatalogAliases {
    val renames: Map<String, String> = mapOf(
        "trigger.manual" to "spark.manual",
        "trigger.schedule" to "spark.schedule",
        "trigger.webhook" to "spark.webhook",
        "trigger.share" to "spark.share",
        "trigger.notification" to "spark.notification",
        "trigger.sms" to "spark.sms",
        "trigger.battery" to "spark.battery",
        "trigger.geofence" to "spark.geofence",
        "trigger.bluetooth" to "spark.bluetooth",
        "trigger.wifi" to "spark.wifi",
        "trigger.headset" to "spark.headset",
        "trigger.boot" to "spark.boot",
        "logic.if" to "flow.if",
        "logic.switch" to "flow.switch",
        "logic.merge" to "flow.merge",
        "logic.loop" to "flow.loop",
        "logic.wait" to "flow.wait",
        "logic.stop" to "flow.stop",
        "logic.onError" to "flow.on_error",
        "logic.subflow" to "flow.subflow",
        "logic.noop" to "flow.noop",
        "logic.filter" to "data.filter",
        "logic.sort" to "data.sort",
        "logic.limit" to "data.limit",
        "logic.dedupe" to "data.dedupe",
        "logic.compare" to "data.compare",
        "transform.set" to "data.set",
        "transform.date" to "data.date",
        "transform.split" to "data.split_out",
        "transform.xml" to "data.xml",
        "transform.html" to "data.html",
        "transform.markdown" to "data.markdown",
        "transform.ical" to "data.ical",
        "transform.pdf" to "files.pdf",
        "transform.crypto" to "security.crypto",
        "transform.totp" to "security.totp",
        "transform.code" to "code.javascript",
        "saas.http" to "net.http",
        "saas.rss" to "net.rss",
        "saas.telegram" to "app.telegram",
        "saas.slack" to "app.slack",
        "saas.discord" to "app.discord",
        "saas.sheets" to "app.google_sheets",
        "saas.notion" to "app.notion",
    )

    val map: AliasMap = AliasMap.build(
        aliases = renames.mapValues { (_, new) -> AliasTarget(new) } +
            StepConverters.merges.mapValues { (_, t) -> AliasTarget(t.first, t.second, autoApply = true) },
        tombstones = StepTombstones.all,
        converters = StepConverters.all,
    )
    val normalizer: StepNormalizer = StepNormalizer(map)
}

/** Delegates everything to [inner] except the spec, which carries the new taxonomy metadata. */
internal class PlacedExecutor(private val inner: StepExecutor) : StepExecutor by inner {
    override val spec: NodeSpec = CategoryDefs.place(inner.spec)
}

/** Steps removed on purpose with no replacement Step. They load as Unavailable Step placeholders. */
object StepTombstones {
    val all: List<com.kaivoflow.core.model.Tombstone> = listOf(
        com.kaivoflow.core.model.Tombstone(
            "saas.tcp", "Raw TCP requests were removed (niche, large security surface, no automation-tool parity). It may return later as an opt-in Protocols Step.",
        ),
        com.kaivoflow.core.model.Tombstone(
            "transform.chart", "The Chart Step was removed (hard-coded theme, low value). It may return later as a proper Step with its own spec.",
        ),
    )
}
