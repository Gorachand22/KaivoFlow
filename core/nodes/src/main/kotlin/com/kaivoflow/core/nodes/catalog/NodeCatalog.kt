package com.kaivoflow.core.nodes

import com.kaivoflow.core.ai.ApprovalCallback
import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.ai.agents.AgentNode
import com.kaivoflow.core.nodes.ai.agents.ToolStepResolver
import com.kaivoflow.core.nodes.ai.chains.AiChatNode
import com.kaivoflow.core.nodes.ai.chains.BasicLlmNode
import com.kaivoflow.core.nodes.ai.models.ChatModelNode
import com.kaivoflow.core.nodes.ai.parsers.StructuredParserNode
import com.kaivoflow.core.nodes.ai.chains.AiClassifyNode
import com.kaivoflow.core.nodes.ai.chains.AiExtractNode
import com.kaivoflow.core.nodes.app.messaging.DiscordNode
import com.kaivoflow.core.nodes.app.messaging.SlackNode
import com.kaivoflow.core.nodes.app.messaging.TelegramNode
import com.kaivoflow.core.nodes.app.productivity.GoogleSheetsNode
import com.kaivoflow.core.nodes.app.productivity.NotionNode
import com.kaivoflow.core.nodes.code.script.CodeNode
import com.kaivoflow.core.nodes.data.items.AggregateNode
import com.kaivoflow.core.nodes.data.items.CompareNode
import com.kaivoflow.core.nodes.data.items.DedupeNode
import com.kaivoflow.core.nodes.data.items.FilterNode
import com.kaivoflow.core.nodes.data.items.LimitNode
import com.kaivoflow.core.nodes.data.items.SetFieldsNode
import com.kaivoflow.core.nodes.data.items.SortNode
import com.kaivoflow.core.nodes.data.items.SplitNode
import com.kaivoflow.core.nodes.data.items.StatsNode
import com.kaivoflow.core.nodes.data.storage.TableNode
import com.kaivoflow.core.nodes.data.text.HtmlNode
import com.kaivoflow.core.nodes.data.text.IcalNode
import com.kaivoflow.core.nodes.data.text.MarkdownNode
import com.kaivoflow.core.nodes.data.text.XmlNode
import com.kaivoflow.core.nodes.data.time.DateNode
import com.kaivoflow.core.nodes.device.connectivity.NetworkStatusNode
import com.kaivoflow.core.nodes.device.contacts_calendar.CalendarEventNode
import com.kaivoflow.core.nodes.device.contacts_calendar.ContactLookupNode
import com.kaivoflow.core.nodes.device.files_media.FileNode
import com.kaivoflow.core.nodes.device.io.ClipboardNode
import com.kaivoflow.core.nodes.device.io.ShareTextNode
import com.kaivoflow.core.nodes.device.io.SpeakNode
import com.kaivoflow.core.nodes.device.io.TorchNode
import com.kaivoflow.core.nodes.device.io.VibrateNode
import com.kaivoflow.core.nodes.device.location.LocationNode
import com.kaivoflow.core.nodes.device.notifications.NotificationSendNode
import com.kaivoflow.core.nodes.device.sensors.BatteryStatusNode
import com.kaivoflow.core.nodes.device.system.OpenLinkNode
import com.kaivoflow.core.nodes.device.system.VolumeNode
import com.kaivoflow.core.nodes.device.telephony.SmsSendNode
import com.kaivoflow.core.nodes.files.convert.BinaryToolsNode
import com.kaivoflow.core.nodes.files.convert.CsvNode
import com.kaivoflow.core.nodes.files.document.PdfNode
import com.kaivoflow.core.nodes.flow.branching.IfNode
import com.kaivoflow.core.nodes.flow.branching.MergeNode
import com.kaivoflow.core.nodes.flow.branching.SwitchNode
import com.kaivoflow.core.nodes.flow.composition.NoopNode
import com.kaivoflow.core.nodes.flow.composition.SubFlowNode
import com.kaivoflow.core.nodes.flow.looping.LoopNode
import com.kaivoflow.core.nodes.flow.timing.ErrorHandlerNode
import com.kaivoflow.core.nodes.flow.timing.StopNode
import com.kaivoflow.core.nodes.flow.timing.WaitNode
import com.kaivoflow.core.nodes.net.web.HttpNode
import com.kaivoflow.core.nodes.net.web.RssNode
import com.kaivoflow.core.nodes.security.auth.TotpNode
import com.kaivoflow.core.nodes.security.crypto.CryptoNode
import com.kaivoflow.core.nodes.spark.device_events.BatteryTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.BluetoothTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.BootTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.GeofenceTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.HeadsetTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.NotificationTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.SmsTriggerNode
import com.kaivoflow.core.nodes.spark.device_events.WifiTriggerNode
import com.kaivoflow.core.nodes.spark.incoming.ShareTriggerNode
import com.kaivoflow.core.nodes.spark.incoming.WebhookTriggerNode
import com.kaivoflow.core.nodes.spark.start.ManualTriggerNode
import com.kaivoflow.core.nodes.spark.start.ScheduleTriggerNode

/**
 * The single list of every Step KaivoFlow can run. The palette, search, inspector forms, the
 * validator and the executor instantiation all read from [all], so a Step cannot appear in the UI
 * without being runnable.
 */
object NodeCatalog {

    /**
     * [approvals] is who may answer "may the agent do this?" for tools that act on the world. It is asked only in
     * a Test Step or a hand-started Run (never in Production, never from the always-on engine), and a callback that
     * has no screen to show a card denies at once, so passing one cannot make a Run hang.
     */
    fun executors(devices: DeviceGateway = NoopDeviceGateway, approvals: ApprovalCallback? = null): List<StepExecutor> {
        // The Agent offers existing Steps as tools, so it needs to find them by id. It looks them up in
        // the placed list (specs with lifecycle), which only exists once the list is built: bind it after.
        val late = LateToolResolver()
        val placed = rawExecutors(devices, late, approvals).map { PlacedExecutor(it) }
        late.bind(placed)
        return placed
    }

    private class LateToolResolver : ToolStepResolver {
        private var byId: Map<String, StepExecutor> = emptyMap()
        fun bind(list: List<StepExecutor>) { byId = list.associateBy { it.spec.id } }
        override fun executor(type: String): StepExecutor? = byId[type]
    }

    private fun rawExecutors(devices: DeviceGateway, tools: ToolStepResolver, approvals: ApprovalCallback?): List<StepExecutor> = listOf(
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
        DedupeNode(devices),
        LoopNode(),
        WaitNode(),
        StopNode(),
        ErrorHandlerNode(),
        SortNode(),
        LimitNode(),
        CompareNode(),
        TableNode(devices),
        NoopNode(),
        SubFlowNode(),
        // Transform
        SetFieldsNode(),
        DateNode(),
        AggregateNode(),
        CodeNode(),
        CryptoNode(),
        StatsNode(),
        SplitNode(),
        CsvNode(),
        XmlNode(),
        HtmlNode(),
        MarkdownNode(),
        IcalNode(),
        BinaryToolsNode(devices),
        TotpNode(),
        PdfNode(devices),
        // AI
        AiChatNode(),
        AiExtractNode(),
        AiClassifyNode(),
        AgentNode(tools, approvals = approvals),
        ChatModelNode(),
        BasicLlmNode(),
        StructuredParserNode(),
        // SaaS
        HttpNode(devices),
        GoogleSheetsNode(),
        NotionNode(),
        TelegramNode(),
        SlackNode(),
        DiscordNode(),
        RssNode(),
        // Device
        NotificationSendNode(devices),
        SmsSendNode(devices),
        ClipboardNode(devices),
        OpenLinkNode(devices),
        SpeakNode(devices),
        VibrateNode(devices),
        FileNode(devices),
        BatteryStatusNode(devices),
        NetworkStatusNode(devices),
        ShareTextNode(devices),
        TorchNode(devices),
        VolumeNode(devices),
        LocationNode(devices),
        ContactLookupNode(devices),
        CalendarEventNode(devices),
    )

    fun registry(devices: DeviceGateway = NoopDeviceGateway, approvals: ApprovalCallback? = null): NodeRegistry = NodeRegistry(executors(devices, approvals))

    /** Used by tests and by the "is my catalog complete?" check. */
    fun countByCategory(devices: DeviceGateway = NoopDeviceGateway): Map<StepCategory, Int> =
        registry(devices).specs.groupingBy { it.category }.eachCount()
}
