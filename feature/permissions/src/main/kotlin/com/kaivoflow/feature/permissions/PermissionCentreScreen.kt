package com.kaivoflow.feature.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.StatusBadge

/**
 * A permission KaivoFlow can ask for, plus the honest reason.
 *
 * Pillar 4 requires that every sensitive permission be (a) optional, (b) explained in-app before the
 * system dialog, and (c) useless on its own. Nothing here is requested at first launch — the user
 * only meets these when a Flow they built needs one.
 */
enum class KaivoPermission(
    val key: String,
    val title: String,
    val promise: String,
    val whyNow: String,
    val isSensitive: Boolean,
    val manifestPermission: String?,
) {
    Notifications(
        key = "notifications",
        title = "Show notifications",
        promise = "So KaivoFlow can tell you when a Run finishes or fails — and so a Notification Spark can react to your other apps.",
        whyNow = "Without this, Flows still run; you just won't see the result banner.",
        isSensitive = false,
        manifestPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
    ),
    NotificationAccess(
        key = "notification_access",
        title = "Read notifications",
        promise = "Only the apps you name in a Notification Spark are looked at, and only while that Flow is switched on.",
        whyNow = "Needed for Sparks like \"when my bank sends an SMS-like alert, log it\".",
        isSensitive = true,
        manifestPermission = null, // granted through Settings, not a runtime dialog
    ),
    Sms(
        key = "sms",
        title = "Read and send SMS",
        promise = "KaivoFlow reads an incoming SMS only when an SMS Spark is enabled, and sends SMS only when a Step you wrote does.",
        whyNow = "Needed for Sparks like \"when a bank message arrives, extract the amount\".",
        isSensitive = true,
        manifestPermission = Manifest.permission.RECEIVE_SMS,
    ),
    ExactAlarms(
        key = "exact_alarms",
        title = "Run at an exact time",
        promise = "Scheduled Flows fire on the minute instead of within a window.",
        whyNow = "Without this, an 8:00 reminder might arrive at 8:15.",
        isSensitive = true,
        manifestPermission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.SCHEDULE_EXACT_ALARM else null,
    ),
}

/** Runtime state of one permission, as the UI needs to show it. */
data class PermissionStatus(
    val permission: KaivoPermission,
    val granted: Boolean,
)

fun Context.statusOf(permission: KaivoPermission): PermissionStatus {
    val granted = when (permission) {
        KaivoPermission.NotificationAccess ->
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        else -> permission.manifestPermission?.let {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        } ?: true
    }
    return PermissionStatus(permission, granted)
}

/**
 * In-app disclosure (Play policy): shown BEFORE the system prompt, in plain language, with a decline
 * path that still leaves the app usable.
 */
@Composable
fun PermissionCentreScreen(
    onBack: () -> Unit,
    onRequest: (KaivoPermission) -> Unit,
) {
    val context = LocalContext.current
    val colors = KaivoTheme.colors
    var statuses by remember { mutableStateOf(KaivoPermission.entries.map { context.statusOf(it) }) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Permissions", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "KaivoFlow asks for as little as it can. Every item below is optional — a Flow that does not use it keeps working.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(16.dp))

        SectionHeader(title = "What KaivoFlow may ask for")
        Spacer(Modifier.height(10.dp))
        statuses.forEach { status ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(KaivoShapes.stepCard)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            status.permission.title,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = colors.inkPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(
                            if (status.granted) "Granted" else "Not granted",
                            if (status.granted) BadgeKind.Success else BadgeKind.Neutral,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(status.permission.promise, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(status.permission.whyNow, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
                if (!status.granted) {
                    Spacer(Modifier.width(10.dp))
                    GhostButton(
                        text = "Allow",
                        onClick = {
                            onRequest(status.permission)
                            statuses = KaivoPermission.entries.map { context.statusOf(it) }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "You can revoke any of these in Android Settings at any time. Flows that needed it will show a clear warning instead of failing silently.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(24.dp))
    }
}
