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
        GeofenceTriggerNode(),
        BluetoothTriggerNode(),
        WifiTriggerNode(),
        HeadsetTriggerNode(),
        BootTriggerNode(),
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
        SortNode(),
        LimitNode(),
        CompareNode(),
        TableNode(devices),
        RunInfoNode(),
        NoopNode(),
        SubFlowNode(),
        PollChangesNode(devices),
        // Transform
        SetFieldsNode(),
        FieldsNode(),
        JsonNode(),
        TemplateNode(),
        DateNode(),
        AggregateNode(),
        CodeNode(),
        CryptoNode(),
        MathNode(),
        TextNode(),
        StatsNode(),
        SplitNode(),
        UrlNode(),
        CsvNode(),
        XmlNode(),
        HtmlNode(),
        MarkdownNode(),
        IcalNode(),
        BinaryToolsNode(devices),
        BinaryUploadNode(devices),
        ChartNode(devices),
        RandomNode(),
        TokenNode(),
        TotpNode(),
        PdfNode(devices),
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
        RssNode(),
        GraphQlNode(),
        WebDavNode(devices),
        TcpNode(),
        ServiceConnectorNode(),
        // Device
        NotificationSendNode(devices),
        SmsSendNode(devices),
        ClipboardNode(devices),
        ClipboardReadNode(devices),
        OpenLinkNode(devices),
        SpeakNode(devices),
        VibrateNode(devices),
        FileNode(devices),
        BatteryStatusNode(devices),
        NetworkStatusNode(devices),
        ToastNode(devices),
        ShareTextNode(devices),
        TorchNode(devices),
        VolumeNode(devices),
        LocationNode(devices),
        WifiInfoNode(devices),
        ContactLookupNode(devices),
        CalendarEventNode(devices),
    )

    fun registry(devices: DeviceGateway = NoopDeviceGateway): NodeRegistry = NodeRegistry(executors(devices))

    /** Used by tests and by the "is my catalog complete?" check. */
    fun countByCategory(devices: DeviceGateway = NoopDeviceGateway): Map<StepCategory, Int> =
        registry(devices).specs.groupingBy { it.category }.eachCount()
}
