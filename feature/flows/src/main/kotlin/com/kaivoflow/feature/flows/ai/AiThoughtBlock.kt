package com.kaivoflow.feature.flows.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoTheme

/**
 * Renders an agent's reasoning trace and activity steps in an elegant, collapsible accordion.
 * Supports real-time streaming: live duration ticker, pulsing activity subline, and clickable
 * green monospaced tool JSON payloads.
 */
@Composable
fun AiThoughtBlock(
    thought: AiThoughtTrace,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    val colors = KaivoTheme.colors
    val isStreaming = thought.isActive
    var isExpanded by remember { mutableStateOf(initialExpanded) }
    val chevronRotation by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "thought_chevron")

    // Pulse animation for live streaming activity indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    // Tracks which activity steps have their input/output JSON expanded
    val expandedSteps = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // Header Row: "Thinking... (Xs)" or "Thought for Xs v"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            val headerText = if (isStreaming) {
                if (thought.durationSec < 1) "Thinking..." else "Thinking... (${thought.durationSec}s)"
            } else {
                "Thought for ${thought.durationSec.coerceAtLeast(1)}s"
            }

            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.royalGold)
                        .alpha(pulseAlpha),
                )
            }

            Text(
                text = headerText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = if (isStreaming) colors.royalGold else colors.inkSecondary,
            )

            if (!isStreaming) {
                Icon(
                    KaivoIcons.icon("zap"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(12.dp),
                )
            }

            Icon(
                KaivoIcons.icon("chevron_down"),
                contentDescription = if (isExpanded) "Collapse thought" else "Expand thought",
                tint = colors.inkSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation),
            )
        }

        // Live activity subline when streaming and collapsed
        if (isStreaming && !isExpanded && thought.currentActivity != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "● ${thought.currentActivity}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.royalGold.copy(alpha = 0.85f),
                    maxLines = 1,
                )
                Text(
                    text = " · ${thought.durationSec}s",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = colors.inkSecondary,
                )
            }
        }

        // Collapsible Content
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                    .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                    .background(colors.surfaceCard.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                // Steps Rail
                thought.steps.forEachIndexed { index, step ->
                    val isStepExpanded = expandedSteps[step.id] ?: false
                    val hasPayload = !step.inputJson.isNullOrBlank() || !step.resultJson.isNullOrBlank()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = hasPayload) {
                                expandedSteps[step.id] = !isStepExpanded
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Timeline dot
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (step.isSuccess) colors.royalGold else colors.statusError),
                            )

                            Spacer(Modifier.width(10.dp))

                            Text(
                                text = step.label,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                color = colors.inkPrimary,
                                modifier = Modifier.weight(1f),
                            )

                            if (hasPayload) {
                                Icon(
                                    KaivoIcons.icon("zap"),
                                    contentDescription = null,
                                    tint = colors.royalGold.copy(alpha = 0.7f),
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    KaivoIcons.icon(if (isStepExpanded) "chevron_up" else "chevron_down"),
                                    contentDescription = if (isStepExpanded) "Hide payload" else "Show payload",
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }

                        // Expandable Green/Cyan Monospaced JSON Payload Accordion
                        AnimatedVisibility(
                            visible = isStepExpanded && hasPayload,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
                            ) {
                                if (!step.inputJson.isNullOrBlank()) {
                                    JsonPayloadCard(
                                        title = null,
                                        jsonContent = step.inputJson,
                                        accentColor = Color(0xFF22C55E), // Emerald Green
                                    )
                                }

                                if (!step.resultJson.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    JsonPayloadCard(
                                        title = null,
                                        jsonContent = step.resultJson,
                                        accentColor = Color(0xFF38BDF8), // Cyan / Sky Blue
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Reasoning Rationale
                if (!thought.reasoningText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = thought.reasoningText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonPayloadCard(
    title: String?,
    jsonContent: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101216)) // Dark monospaced code background
            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                text = jsonContent,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                ),
                color = accentColor,
            )
        }
    }
}
