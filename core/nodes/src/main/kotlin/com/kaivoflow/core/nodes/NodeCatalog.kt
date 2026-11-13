package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.model.StepCategory

/**
 * The single list of every Step KaivoFlow can run. The palette, search, inspector forms, the
 * validator and the executor instantiation all read from [all], so a Step cannot appear in the UI
 * without being runnable.
 */
object NodeCatalog {

    fun executors(devices: DeviceGateway = NoopDeviceGateway): List<StepExecutor> = listOf(
        // Sparks
        ManualTriggerNode(),
        ScheduleTriggerNode(),
        WebhookTriggerNode(),
        NotificationTriggerNode(),
        SmsTriggerNode(),
        ShareTriggerNode(),
        BatteryTriggerNode(),
        // Logic
        IfNode(),
        SwitchNode(),
        MergeNode(),
        FilterNode(),
        DedupeNode(),
        LoopNode(),
        WaitNode(),
        StopNode(),
        ErrorHandlerNode(),
        // Transform
        SetFieldsNode(),
        FieldsNode(),
        JsonNode(),
        TemplateNode(),
        DateNode(),
        AggregateNode(),
        CodeNode(),
        // AI
        AiChatNode(),
        AiExtractNode(),
        AiClassifyNode(),
        // SaaS
        HttpNode(),
        GoogleSheetsNode(),
        NotionNode(),
        TelegramNode(),
        SlackNode(),
        DiscordNode(),
        WeatherNode(),
        // Device
        NotificationSendNode(devices),
        SmsSendNode(devices),
        ClipboardNode(devices),
        OpenLinkNode(devices),
        SpeakNode(devices),
        VibrateNode(devices),
        FileNode(devices),
    )

    fun registry(devices: DeviceGateway = NoopDeviceGateway): NodeRegistry = NodeRegistry(executors(devices))

    /** Used by tests and by the "is my catalog complete?" check. */
    fun countByCategory(devices: DeviceGateway = NoopDeviceGateway): Map<StepCategory, Int> =
        registry(devices).specs.groupingBy { it.category }.eachCount()
}
