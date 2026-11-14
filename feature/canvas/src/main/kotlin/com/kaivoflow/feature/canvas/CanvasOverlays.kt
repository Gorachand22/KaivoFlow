package com.kaivoflow.feature.canvas

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GlassPill
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.feature.canvas.layout.Rect2
import com.kaivoflow.feature.canvas.layout.StepLayout

/**
 * Floating HUD shown while a Run is in flight: progress, elapsed time, current Step, Cancel.
 * Glass, one-handed, thumb-reachable — never a dialog that blocks the canvas.
 */
@Composable
fun RunHud(
    run: RunUiState,
    flowName: String,
    onCancel: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val progress by animateFloatAsState(run.progress, label = "runProgress")

    Box(modifier) {
        GlassPill {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (run.isRunning) {
                    CircularProgressIndicator(
                        color = colors.royalGold,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        imageVector = KaivoIcons.icon(
                            when (run.status) {
                                com.kaivoflow.core.model.RunStatus.Succeeded -> "checkCircle"
                                com.kaivoflow.core.model.RunStatus.Failed -> "error"
                                else -> "info"
                            }
                        ),
                        contentDescription = null,
                        tint = when (run.status) {
                            com.kaivoflow.core.model.RunStatus.Succeeded -> colors.statusSuccess
                            com.kaivoflow.core.model.RunStatus.Failed -> colors.statusError
                            else -> colors.inkSecondary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (run.isRunning) flowName else statusText(run),
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (run.isRunning) {
                            "${(progress * 100).toInt()}% • ${run.elapsedMs / 1000}s"
                        } else {
                            "${run.elapsedMs / 1000}s • ${run.completedSteps}/${run.totalSteps} Steps"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = colors.inkSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable { if (run.isRunning) onCancel() else onOpenHistory() }
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = KaivoIcons.icon(if (run.isRunning) "close" else "history"),
                        contentDescription = if (run.isRunning) "Stop this Run" else "Open Run history",
                        tint = if (run.isRunning) colors.statusError else colors.inkSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun statusText(run: RunUiState): String = when (run.status) {
    com.kaivoflow.core.model.RunStatus.Succeeded -> "Run finished"
    com.kaivoflow.core.model.RunStatus.PartialSuccess -> "Finished with warnings"
    com.kaivoflow.core.model.RunStatus.Failed -> "Run failed"
    com.kaivoflow.core.model.RunStatus.Canceled -> "Run stopped"
    else -> "Ready"
}

/**
 * Translucent minimap. Drawn in one Canvas from the same layout data as the canvas, so it can never
 * disagree with what the user sees. Tap-to-jump.
 */
@Composable
fun Minimap(
    layouts: Collection<StepLayout>,
    activeStepId: String?,
    visible: Rect2,
    onJump: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    if (layouts.isEmpty()) return
    val bounds = com.kaivoflow.feature.canvas.layout.CanvasGeometry.contentBounds(layouts)
    if (bounds.width <= 0f || bounds.height <= 0f) return

    Box(
        modifier
            .size(width = 96.dp, height = 128.dp)
            .clip(KaivoShapes.chip)
            .background(colors.surfaceGlass)
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .clickable { onJump(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f) },
    ) {
        Canvas(Modifier.padding(6.dp)) {
            val scale = minOf(size.width / bounds.width, size.height / bounds.height)
            fun mapX(x: Float) = (x - bounds.left) * scale
            fun mapY(y: Float) = (y - bounds.top) * scale

            drawRect(
                color = colors.royalGold.copy(alpha = 0.35f),
                topLeft = Offset(mapX(visible.left), mapY(visible.top)),
                size = Size(visible.width * scale, visible.height * scale),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * density),
            )
            layouts.forEach { layout ->
                drawRoundRect(
                    color = if (layout.id == activeStepId) colors.royalGold else colors.inkMuted.copy(alpha = 0.6f),
                    topLeft = Offset(mapX(layout.topLeft.x), mapY(layout.topLeft.y)),
                    size = Size(
                        (layout.size.width * scale).coerceAtLeast(3f),
                        (layout.size.height * scale).coerceAtLeast(3f),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * density),
                )
            }
        }
    }
}

/**
 * Prompt-to-Flow: the empty state that turns a sentence into a Flow.
 *
 * Starter chips are real prompts that the (offline) matcher understands, so the very first tap
 * always produces something runnable.
 */
@Composable
fun MagicPromptCard(
    isGenerating: Boolean,
    onGenerate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Column(
        modifier
            .fillMaxWidth()
            .clip(KaivoShapes.sheetTop)
            .background(colors.surfaceCard.copy(alpha = 0.92f))
            .border(1.dp, colors.royalGold.copy(alpha = 0.22f), KaivoShapes.sheetTop)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                KaivoIcons.icon("sparkles"),
                contentDescription = null,
                tint = colors.velvetRose,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Describe what you want your phone to automate",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary,
            )
        }
        Spacer(Modifier.height(12.dp))

        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. every morning at 8, send me the weather on Telegram") },
            shape = KaivoShapes.stepCard,
            minLines = 2,
            trailingIcon = {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(colors.velvetRose, colors.royalGold)))
                        .clickable(enabled = !isGenerating && text.isNotBlank()) { onGenerate(text) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(KaivoIcons.icon("sparkles"), "Build my Flow", tint = colors.canvasVoid, modifier = Modifier.size(24.dp))
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Text("Try one of these", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkMuted)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Summarise bank SMS alerts with AI & log them to a sheet",
                "When I share a link, extract the key points and save to Notion",
                "Every morning at 8 AM, fetch the weather and send a Telegram digest",
            ).forEach { starter ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(colors.surfaceElevated.copy(alpha = 0.6f))
                        .clickable { text = starter }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(starter, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
            }
        }
    }
}

/** Small validation banner: what is stopping the Flow from running. */
@Composable
fun ValidationBanner(
    message: String,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.statusError.copy(alpha = 0.12f))
            .border(1.dp, colors.statusError.copy(alpha = 0.35f), KaivoShapes.chip)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaivoIcons.icon("error"), null, tint = colors.statusError, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(CircleShape).clickable(onClick = onFix).padding(6.dp)) {
            Text("Fix", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.statusError)
        }
    }
}

/** Empty canvas hint, shown when a Flow has no Steps at all and the prompt card is dismissed. */
@Composable
fun EmptyCanvasHint(onAddStep: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(KaivoIcons.icon("bolt"), null, tint = colors.inkMuted, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Tap + to add a Spark, then chain Steps below it.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(16.dp))
        GoldButton(text = "Add a Step", icon = "add", onClick = onAddStep)
    }
}
