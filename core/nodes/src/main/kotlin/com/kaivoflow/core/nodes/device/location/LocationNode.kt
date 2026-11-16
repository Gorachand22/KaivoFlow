package com.kaivoflow.core.nodes.device.location

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

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
