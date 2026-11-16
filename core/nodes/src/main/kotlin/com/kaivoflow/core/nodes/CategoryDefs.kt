package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.model.AliasMap
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
        "trigger.manual" to p("spark/start"),
        "trigger.schedule" to p("spark/start"),
        "trigger.webhook" to p("spark/incoming"),
        "trigger.share" to p("spark/incoming"),
        "trigger.notification" to p("spark/device_events", "device/notifications"),
        "trigger.sms" to p("spark/device_events", "device/telephony"),
        "trigger.battery" to p("spark/device_events", "device/sensors"),
        "trigger.geofence" to p("spark/device_events", "device/location"),
        "trigger.bluetooth" to p("spark/device_events", "device/connectivity"),
        "trigger.wifi" to p("spark/device_events", "device/connectivity"),
        "trigger.headset" to p("spark/device_events", "device/io"),
        "trigger.boot" to p("spark/device_events", "device/system"),
        // 3.2 Logic
        "logic.if" to p("flow/branching"),
        "logic.switch" to p("flow/branching"),
        "logic.merge" to p("flow/branching"),
        "logic.loop" to p("flow/looping"),
        "logic.wait" to p("flow/timing"),
        "logic.stop" to p("flow/timing"),
        "logic.onError" to p("flow/timing"),
        "logic.subflow" to p("flow/composition"),
        "logic.noop" to p("flow/composition"),
        "logic.filter" to p("data/items"),
        "logic.sort" to p("data/items"),
        "logic.limit" to p("data/items"),
        "logic.dedupe" to p("data/items"),
        "logic.compare" to p("data/items"),
        "logic.poll" to p("data/items"),
        "logic.table" to p("data/storage"),
        "logic.run_info" to p("data/items"),
        // 3.3 Transform
        "transform.set" to p("data/items"),
        "transform.fields" to p("data/items"),
        "transform.template" to p("data/items"),
        "transform.json" to p("data/items"),
        "transform.math" to p("data/items"),
        "transform.text" to p("data/items"),
        "transform.url" to p("data/items"),
        "transform.date" to p("data/time"),
        "transform.split" to p("data/items"),
        "transform.aggregate" to p("data/items"),
        "transform.stats" to p("data/items"),
        "transform.xml" to p("data/text"),
        "transform.html" to p("data/text"),
        "transform.markdown" to p("data/text"),
        "transform.ical" to p("data/text"),
        "transform.csv" to p("files/convert"),
        "transform.binary" to p("files/convert"),
        "transform.binary_upload" to p("net/web"),
        "transform.pdf" to p("files/document"),
        "transform.chart" to p("data/items"),
        "transform.random" to p("security/crypto"),
        "transform.token" to p("security/crypto"),
        "transform.crypto" to p("security/crypto"),
        "transform.totp" to p("security/auth"),
        "transform.code" to p("code/script"),
        // 3.4 AI (legacy trio, replaced later)
        "ai.chat" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        "ai.extract" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        "ai.classify" to p("ai/chains", lifecycle = Lifecycle.Legacy),
        // 3.5 SaaS
        "saas.http" to p("net/web"),
        "saas.connector" to p("net/web"),
        "saas.graphql" to p("net/web"),
        "saas.webdav" to p("net/web"),
        "saas.rss" to p("net/web"),
        "saas.tcp" to p("net/protocols"),
        "saas.weather" to p("net/web"),
        "saas.telegram" to p("app/messaging"),
        "saas.slack" to p("app/messaging"),
        "saas.discord" to p("app/messaging"),
        "saas.sheets" to p("app/productivity"),
        "saas.notion" to p("app/productivity"),
        // 3.6 Device
        "device.notify" to p("device/notifications"),
        "device.toast" to p("device/notifications"),
        "device.sms" to p("device/telephony"),
        "device.contact" to p("device/contacts_calendar"),
        "device.calendar_event" to p("device/contacts_calendar"),
        "device.location" to p("device/location"),
        "device.battery_status" to p("device/sensors"),
        "device.network_status" to p("device/connectivity"),
        "device.wifi_info" to p("device/connectivity"),
        "device.file" to p("device/files_media"),
        "device.open" to p("device/system"),
        "device.volume" to p("device/system"),
        "device.clipboard" to p("device/io"),
        "device.clipboard_read" to p("device/io"),
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

/** Aliases and converters for saved Flows (taxonomy spec section 6). Empty until step 5.7.6 renames ids. */
object CatalogAliases {
    val map: AliasMap = AliasMap.EMPTY
    val normalizer: StepNormalizer = StepNormalizer(map)
}

/** Delegates everything to [inner] except the spec, which carries the new taxonomy metadata. */
internal class PlacedExecutor(private val inner: StepExecutor) : StepExecutor by inner {
    override val spec: NodeSpec = CategoryDefs.place(inner.spec)
}
