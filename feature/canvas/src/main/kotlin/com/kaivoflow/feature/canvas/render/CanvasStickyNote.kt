package com.kaivoflow.feature.canvas.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.StickyNote

/**
 * Freeform canvas documentation note card placed in world coordinates.
 */
@Composable
fun CanvasStickyNote(
    note: StickyNote,
    pixelScale: Float,
    density: Density,
    zoom: Float,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val jewelColor = jewelToneColor(note.color)

    Box(
        modifier
            .graphicsLayer {
                translationX = note.x * pixelScale
                translationY = note.y * pixelScale
            }
            .width(note.width.dp)
            .height(note.height.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .border(1.dp, jewelColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onEdit),
    ) {
        Column {
            // Note Header Bar (Drag handle + Title + Actions)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(jewelColor.copy(alpha = 0.15f))
                    .pointerInput(note.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = with(density) { dragAmount.x.toDp().value / zoom }
                            val dy = with(density) { dragAmount.y.toDp().value / zoom }
                            onDrag(dx, dy)
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(jewelColor),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        note.title.ifBlank { "Note" },
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onEdit)
                            .padding(4.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("edit"),
                            contentDescription = "Edit Note",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onDelete)
                            .padding(4.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("close"),
                            contentDescription = "Delete Note",
                            tint = colors.inkMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            // Note Content Body
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(10.dp),
            ) {
                Text(
                    text = note.content.ifBlank { "Tap to write note…" },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (note.content.isBlank()) colors.inkMuted else colors.inkSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
