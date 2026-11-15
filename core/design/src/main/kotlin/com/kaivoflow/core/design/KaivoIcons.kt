package com.kaivoflow.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.Wifi
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
        "flow" to Icons.Filled.Route,
        "plug" to Icons.Filled.Api,
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
        "group" to Icons.Filled.Folder,
        "grid" to Icons.Filled.GridView,
        "bookmark" to Icons.Filled.Bookmark,
        "note" to Icons.Filled.TextSnippet,
        "disconnect" to Icons.Filled.CallSplit,
        "variable" to Icons.Filled.Code,
        "add" to Icons.Filled.Add,
        "remove" to Icons.Filled.Remove,
        "center" to Icons.Filled.CenterFocusStrong,
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
        "undo" to Icons.AutoMirrored.Filled.Undo,
        "redo" to Icons.AutoMirrored.Filled.Redo,
        "history" to Icons.Filled.History,
        "bolt" to Icons.Filled.Bolt,
        "info" to Icons.Filled.Info,
        "mail" to Icons.Filled.Email,
        "inbox" to Icons.Filled.Inbox,
        "water" to Icons.Filled.WaterDrop,
        "language" to Icons.Filled.Language,
        "card" to Icons.Filled.CreditCard,
        "scale" to Icons.Filled.Scale,
        // Extended catalog (Sparks, data tools, protocol Steps, device hardware).
        "location" to Icons.Filled.LocationOn,
        "map" to Icons.Filled.Map,
        "bluetooth" to Icons.Filled.Bluetooth,
        "wifi" to Icons.Filled.Wifi,
        "headset" to Icons.Filled.Headset,
        "power" to Icons.Filled.PowerSettingsNew,
        "flashOn" to Icons.Filled.FlashOn,
        "calculate" to Icons.Filled.Calculate,
        "numbers" to Icons.Filled.Numbers,
        "percent" to Icons.Filled.Percent,
        "textFormat" to Icons.Filled.TextFormat,
        "table" to Icons.Filled.TableChart,
        "dataObject" to Icons.Filled.DataObject,
        "html" to Icons.Filled.Html,
        "markdown" to Icons.Filled.TextSnippet,
        "queryStats" to Icons.Filled.QueryStats,
        "timeline" to Icons.Filled.Timeline,
        "token" to Icons.Filled.Token,
        "pin" to Icons.Filled.Pin,
        "lock" to Icons.Filled.Lock,
        "attachFile" to Icons.Filled.AttachFile,
        "download" to Icons.Filled.Download,
        "upload" to Icons.Filled.Upload,
        "storage" to Icons.Filled.Storage,
        "archive" to Icons.Filled.Archive,
        "compress" to Icons.Filled.Compress,
        "shuffle" to Icons.Filled.Shuffle,
        "sortByAlpha" to Icons.Filled.SortByAlpha,
        "filterList" to Icons.Filled.FilterList,
        "difference" to Icons.Filled.Difference,
        "syncAlt" to Icons.Filled.SyncAlt,
        "unfoldMore" to Icons.Filled.UnfoldMore,
        "contacts" to Icons.Filled.Contacts,
        "eventNote" to Icons.Filled.EventNote,
        "translate" to Icons.Filled.Translate,
        "api" to Icons.Filled.Api,
        "rssFeed" to Icons.Filled.RssFeed,
        "photoCamera" to Icons.Filled.PhotoCamera,
        "image" to Icons.Filled.Image,
        "audiotrack" to Icons.Filled.Audiotrack,
    )

    fun icon(key: String): ImageVector = byKey[key] ?: Icons.Filled.Bolt
}
