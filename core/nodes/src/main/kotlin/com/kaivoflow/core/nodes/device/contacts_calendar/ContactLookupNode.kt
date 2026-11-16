package com.kaivoflow.core.nodes.device.contacts_calendar

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

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
