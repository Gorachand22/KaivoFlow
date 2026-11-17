package com.kaivoflow.feature.flows.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Animated "Tinkering..." status pill indicating live agent processing or model discovery.
 * Matches the sovereign aesthetic with an animated dot-matrix grid and floating dark capsule.
 */
@Composable
fun TinkeringPill(
    modifier: Modifier = Modifier,
    text: String = "Tinkering...",
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tinkering")
    val animPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = Color(0xFF141417),
        border = BorderStroke(1.dp, Color(0xFF2E2E38)),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 3x3 Animated Matrix Grid
            Canvas(modifier = Modifier.size(16.dp)) {
                val dotSize = 2.8.dp.toPx()
                val gap = 2.2.dp.toPx()
                val total = 3 * dotSize + 2 * gap
                val startX = (size.width - total) / 2
                val startY = (size.height - total) / 2

                for (row in 0..2) {
                    for (col in 0..2) {
                        val index = row * 3 + col
                        val wave = kotlin.math.sin(animPhase + index * 0.7f)
                        val alpha = (0.35f + 0.65f * ((wave + 1f) / 2f)).coerceIn(0.2f, 1f)
                        drawCircle(
                            color = Color.White.copy(alpha = alpha),
                            radius = dotSize / 2,
                            center = Offset(
                                x = startX + col * (dotSize + gap) + dotSize / 2,
                                y = startY + row * (dotSize + gap) + dotSize / 2,
                            ),
                        )
                    }
                }
            }

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = 0.3.sp,
                ),
            )
        }
    }
}
