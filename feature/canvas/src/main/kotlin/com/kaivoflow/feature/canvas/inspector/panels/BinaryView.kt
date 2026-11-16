package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoSurfaceCard
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge

/**
 * Binary view: one card per attachment with file name, MIME badge, size and checksum, and the
 * actions Open, Share, Copy name and Map name. Image thumbnails need an image loader the project
 * does not have yet, so cards show the type icon; the host can add a preview through Open.
 */
@Composable
fun BinaryView(
    entries: List<BinaryEntry>,
    origin: PanelOrigin,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    query: String = "",
) {
    val colors = KaivoTheme.colors
    val shown = BinaryEntries.filter(entries, query)
    if (shown.isEmpty()) {
        Box(modifier.padding(16.dp)) {
            EmptyStateBlock(title = if (entries.isEmpty()) "No attachments" else "No matching attachments")
        }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items = shown, key = { "${it.itemIndex}/${it.name}" }) { entry ->
            val ref = entry.ref
            val nameSource = PanelSources.binaryFileName(origin, entry.name, entry.itemIndex)
            KaivoSurfaceCard(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "${ref.fileName}, ${ref.mimeType}, ${PanelFormat.bytes(ref.sizeBytes)}, item ${entry.itemIndex + 1}" },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(KaivoShapes.medallion).background(colors.royalGoldDeep.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KaivoIcons.icon(entry.iconKey), contentDescription = null, tint = colors.royalGoldDeep, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ref.fileName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Item ${entry.itemIndex + 1} · ${entry.name}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBadge(ref.mimeType)
                        Text(PanelFormat.bytes(ref.sizeBytes), style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = colors.inkSecondary)
                    }
                    ref.checksum?.let { sum ->
                        Text(
                            "sha256 ${PanelFormat.shortChecksum(sum)}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                            color = colors.inkMuted,
                            maxLines = 1,
                        )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        callbacks.onOpenBinary?.let { open -> KaivoChip("Open", { open(entry.item, entry.name) }, iconKey = "external") }
                        callbacks.onShareBinary?.let { share -> KaivoChip("Share", { share(entry.item, entry.name) }, iconKey = "share") }
                        KaivoChip("Copy name", { callbacks.onCopy("file name", ref.fileName) }, iconKey = "copy")
                        KaivoChip("Map name", { callbacks.onMapTo(nameSource) }, iconKey = "add")
                    }
                }
            }
        }
    }
}
