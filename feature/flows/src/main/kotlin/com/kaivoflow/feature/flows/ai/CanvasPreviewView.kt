package com.kaivoflow.feature.flows.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode

/**
 * Mobile-optimized Visual Canvas Preview.
 * Renders the proposed workflow changes side-by-side or as a live canvas tab.
 * Native sovereign visual node graph editor.
 */
@Composable
fun CanvasPreviewView(
    flow: Flow,
    baseFlow: Flow?,
    onApply: () -> Unit,
    onBackToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header stats banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF18181E))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    KaivoIcons.icon("flow"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = flow.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "${flow.steps.size} Steps · ${flow.streams.size} Connections · Live Preview",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(KaivoShapes.chip)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.chip)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Preview Mode",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.royalGold,
                    )
                }
            }

            // Visual Step Timeline Nodes
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(flow.steps, key = { _, step -> step.id }) { index, step ->
                    val isNewStep = baseFlow != null && baseFlow.steps.none { it.id == step.id }
                    val isModified = baseFlow != null && baseFlow.steps.any { it.id == step.id && it.params != step.params }

                    CanvasNodeCard(
                        step = step,
                        index = index + 1,
                        isNew = isNewStep,
                        isModified = isModified,
                    )

                    if (index < flow.steps.lastIndex) {
                        CanvasConnectionLine()
                    }
                }
            }

            // Bottom Apply Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF18181E))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onBackToChat,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceCard, contentColor = colors.inkPrimary),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back to Chat")
                }

                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.weight(1.5f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(KaivoIcons.icon("check"), contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Apply to Canvas",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasNodeCard(
    step: StepNode,
    index: Int,
    isNew: Boolean,
    isModified: Boolean,
) {
    val colors = KaivoTheme.colors
    val borderColor = when {
        isNew -> Color(0xFF10B981) // Emerald
        isModified -> colors.royalGold
        step.isTrigger -> colors.royalGold.copy(alpha = 0.5f)
        else -> colors.hairline
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F26)),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (step.isTrigger) colors.royalGold.copy(alpha = 0.2f) else Color(0xFF282832)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$index",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (step.isTrigger) colors.royalGold else colors.inkPrimary,
                    )
                }

                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = step.name,
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        if (isNew) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("+ New Step", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (isModified) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.royalGold.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Modified", color = colors.royalGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        text = step.type,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }

            // Show key parameters if any
            if (step.params.isNotEmpty()) {
                val script = step.paramString("script").takeIf { it.isNotBlank() }
                val url = step.paramString("url").takeIf { it.isNotBlank() }
                val title = step.paramString("title").takeIf { it.isNotBlank() }

                val summary = script?.let { "Script: ${it.take(60)}..." }
                    ?: url?.let { "URL: $it" }
                    ?: title?.let { "Title: \"$it\"" }
                    ?: "Params: ${step.params.keys.joinToString(", ")}"

                Text(
                    text = summary,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF141418))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CanvasConnectionLine() {
    val colors = KaivoTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(18.dp)
                .background(colors.royalGold.copy(alpha = 0.6f))
        )
        Icon(
            imageVector = KaivoIcons.icon("arrow_down"),
            contentDescription = null,
            tint = colors.royalGold,
            modifier = Modifier.size(12.dp)
        )
    }
}
