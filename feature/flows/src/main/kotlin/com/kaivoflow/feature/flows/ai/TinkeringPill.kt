package com.kaivoflow.feature.flows.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Animated thinking status pill indicating live agent processing or model discovery.
 * Cycles dynamically through whimsical creation verbs accompanied by an animated
 * dot-matrix grid and an iridescent ambient multi-color glow (cyan, purple, gold, orange).
 */
@Composable
fun TinkeringPill(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val whimsicalWords = remember {
        listOf(
            "Tinkering…",
            "Imagining…",
            "Pondering…",
            "Brewing…",
            "Hatching…",
            "Concocting…",
            "Sparkling…",
            "Noodling…",
            "Percolating…",
            "Doodling…",
        )
    }

    var wordIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(text) {
        if (text == null) {
            while (isActive) {
                delay(2000)
                wordIndex = (wordIndex + 1) % whimsicalWords.size
            }
        }
    }

    val currentDisplayWord = text ?: whimsicalWords[wordIndex]

    val infiniteTransition = rememberInfiniteTransition(label = "tinkering")
    val animPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Multi-color ambient glow beneath the pill (aurora glow)
        Box(
            modifier = Modifier
                .size(width = 150.dp, height = 30.dp)
                .blur(14.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF00E5FF).copy(alpha = glowAlpha * 0.7f),
                            Color(0xFFAA00FF).copy(alpha = glowAlpha),
                            Color(0xFFFFD600).copy(alpha = glowAlpha * 0.8f),
                            Color(0xFFFF3D00).copy(alpha = glowAlpha * 0.6f),
                        )
                    ),
                    shape = RoundedCornerShape(percent = 50),
                )
        )

        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = Color(0xFF101014),
            border = BorderStroke(1.dp, Color(0xFF2C2C38)),
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

                AnimatedContent(
                    targetState = currentDisplayWord,
                    transitionSpec = {
                        (slideInVertically { height -> height / 2 } + fadeIn()) togetherWith
                                (slideOutVertically { height -> -height / 2 } + fadeOut())
                    },
                    label = "whimsicalWordTransition",
                ) { targetWord ->
                    Text(
                        text = targetWord,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            letterSpacing = 0.3.sp,
                        ),
                    )
                }
            }
        }
    }
}
