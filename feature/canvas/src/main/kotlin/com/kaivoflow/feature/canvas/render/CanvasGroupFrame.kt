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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.FlowGroup
import com.kaivoflow.feature.canvas.layout.StepLayout

/**
 * Visual bounding frame that groups related Steps together on Canvas.
 * Moving the header moves all member steps in unison.
 */
@Composable
fun CanvasGroupFrame(
    group: FlowGroup,
    memberLayouts: List<StepLayout>,
    pixelScale: Float,
    density: Density,
    zoom: Float,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val jewelColor = jewelToneColor(group.color)

    val minX = memberLayouts.minOfOrNull { it.topLeft.x } ?: 40f
    val minY = memberLayouts.minOfOrNull { it.topLeft.y } ?: 40f
    val maxX = memberLayouts.maxOfOrNull { it.topLeft.x + it.size.width } ?: (minX + 260f)
    val maxY = memberLayouts.maxOfOrNull { it.topLeft.y + it.size.height } ?: (minY + 160f)

    val pad = 24f
    val headerH = 44f
    val frameX = minX - pad
    val frameY = minY - pad - headerH
    val frameW = (maxX - minX) + pad * 2
    val frameH = (maxY - minY) + pad * 2 + headerH

    Box(
        modifier
            .graphicsLayer {
                translationX = frameX * pixelScale
                translationY = frameY * pixelScale
            }
            .width(frameW.dp)
            .height(frameH.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(jewelColor.copy(alpha = 0.05f))
            .background(colors.surfaceAtelier.copy(alpha = 0.45f))
            .border(1.5.dp, jewelColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
    ) {
        Column {
            // Group Header Bar (Drag handle + Title + Actions)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(jewelColor.copy(alpha = 0.12f))
                    .border(
                        1.dp,
                        jewelColor.copy(alpha = 0.25f),
                        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .pointerInput(group.id) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dx = with(density) { dragAmount.x.toDp().value / zoom }
                            val dy = with(density) { dragAmount.y.toDp().value / zoom }
                            onDrag(dx, dy)
                        }
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(jewelColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        group.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${group.stepIds.size} steps",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onEdit)
                            .padding(6.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("edit"),
                            contentDescription = "Edit Group",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onUngroup)
                            .padding(6.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("close"),
                            contentDescription = "Ungroup",
                            tint = colors.inkMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

fun jewelToneColor(name: String): Color = when (name.lowercase()) {
    "amber" -> Color(0xFFF59E0B)
    "amethyst", "purple" -> Color(0xFF8B5CF6)
    "emerald", "green" -> Color(0xFF10B981)
    "sapphire", "blue" -> Color(0xFF3B82F6)
    "rose", "red" -> Color(0xFFF43F5E)
    "topaz", "teal" -> Color(0xFF06B6D4)
    "gold" -> Color(0xFFD4AF37)
    else -> Color(0xFFD4AF37)
}
