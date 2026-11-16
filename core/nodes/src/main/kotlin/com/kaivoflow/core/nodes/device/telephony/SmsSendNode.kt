package com.kaivoflow.core.nodes.device.telephony

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import kotlinx.collections.immutable.persistentListOf

class SmsSendNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.sms",
        title = "Send an SMS",
        category = StepCategory.Device,
        subtitle = "Text someone from your phone",
        iconKey = "sms",
        params = persistentListOf(
            ParamSpec("to", "Send to", ParamType.Text, required = true, placeholder = "+91…"),
            ParamSpec("body", "Message", ParamType.Multiline, required = true),
            ParamSpec("simSlot", "SIM", ParamType.Number, default = 0, advanced = true),
        ),
        keywords = persistentListOf("sms", "text", "message", "send"),
        requiresSensitivePermission = "android.permission.SEND_SMS",
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        if (!gateway.isSmsPermissionGranted()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "KaivoFlow does not have SMS permission. Open Settings → Privacy centre to grant it.")
        }
        val params = ctx.params()
        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val to = Values.asString(row["to"])
            if (to.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "no phone number to send to")
            gateway.sendSms(to, Values.asString(row["body"]),
                (Values.asDouble(row["simSlot"]) ?: 0.0).toInt())
        }
        return ctx.output(ctx.input)
    }
}
