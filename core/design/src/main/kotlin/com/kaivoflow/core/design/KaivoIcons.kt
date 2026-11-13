package com.kaivoflow.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a [com.kaivoflow.core.model.NodeSpec.iconKey] to a vector icon.
 *
 * Step specs store a string key (not an icon) so the model layer stays free of Compose, and new
 * Steps can be added without touching the canvas.
 */
object KaivoIcons {
    private val byKey: Map<String, ImageVector> = mapOf(
        "touch" to Icons.Filled.Gesture,
        "calendar" to Icons.Filled.Event,
        "schedule" to Icons.Filled.Schedule,
        "alarm" to Icons.Filled.Alarm,
        "webhook" to Icons.Filled.Webhook,
        "eye" to Icons.Filled.Visibility,
        "sms" to Icons.Filled.Sms,
        "share" to Icons.Filled.Share,
        "battery" to Icons.Filled.BatteryAlert,
        "if" to Icons.Filled.CallSplit,
        "switch" to Icons.Filled.Route,
        "merge" to Icons.Filled.Merge,
        "filter" to Icons.Filled.FilterAlt,
        "unique" to Icons.Filled.ContentCut,
        "loop" to Icons.Filled.Loop,
        "clock" to Icons.Filled.Timer,
        "stop" to Icons.Filled.Stop,
        "shield" to Icons.Filled.Shield,
        "edit" to Icons.Filled.Edit,
        "rename" to Icons.Filled.TextFields,
        "braces" to Icons.Filled.Code,
        "text" to Icons.Filled.Description,
        "sum" to Icons.Filled.Summarize,
        "code" to Icons.Filled.Code,
        "sparkles" to Icons.Filled.AutoAwesome,
        "extract" to Icons.Filled.Bookmark,
        "sort" to Icons.Filled.Tune,
        "globe" to Icons.Filled.Public,
        "sheets" to Icons.Filled.GridView,
        "notion" to Icons.Filled.Description,
        "telegram" to Icons.Filled.Send,
        "slack" to Icons.Filled.Forum,
        "discord" to Icons.Filled.Forum,
        "cloud" to Icons.Filled.Cloud,
        "bell" to Icons.Filled.Notifications,
        "clipboard" to Icons.Filled.ContentCopy,
        "external" to Icons.Filled.OpenInNew,
        "speaker" to Icons.Filled.VolumeUp,
        "vibrate" to Icons.Filled.Vibration,
        "file" to Icons.Filled.Folder,
        "add" to Icons.Filled.Add,
        "play" to Icons.Filled.PlayArrow,
        "pause" to Icons.Filled.Pause,
        "stopRun" to Icons.Filled.Stop,
        "check" to Icons.Filled.Check,
        "checkCircle" to Icons.Filled.CheckCircle,
        "error" to Icons.Filled.ErrorOutline,
        "search" to Icons.Filled.Search,
        "settings" to Icons.Filled.Settings,
        "history" to Icons.Filled.History,
        "back" to Icons.Filled.ArrowBack,
        "close" to Icons.Filled.Close,
        "more" to Icons.Filled.MoreVert,
        "delete" to Icons.Filled.Delete,
        "save" to Icons.Filled.Save,
        "refresh" to Icons.Filled.Refresh,
        "key" to Icons.Filled.Key,
        "security" to Icons.Filled.Security,
        "fingerprint" to Icons.Filled.Fingerprint,
        "phone" to Icons.Filled.Smartphone,
        "bolt" to Icons.Filled.Bolt,
        "info" to Icons.Filled.Info,
        "mail" to Icons.Filled.Email,
        "inbox" to Icons.Filled.Inbox,
        "water" to Icons.Filled.WaterDrop,
        "language" to Icons.Filled.Language,
        "card" to Icons.Filled.CreditCard,
        "scale" to Icons.Filled.Scale,
    )

    fun icon(key: String): ImageVector = byKey[key] ?: Icons.Filled.Bolt
}
