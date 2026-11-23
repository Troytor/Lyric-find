package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.AmbientPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-bleed animated liquid mesh backdrop that cross-fades smoothly to the
 * active track's extracted album artwork palette and breathes with live mic beats.
 */
@Composable
fun LiquidGlassBackground(
    palette: AmbientPalette,
    beatIntensity: Float,
    audioAmplitude: Float,
    modifier: Modifier = Modifier,
) {
    val colorSpec = tween<Color>(durationMillis = 1_100)
    val deepBase by animateColorAsState(palette.deepBase, colorSpec, label = "deepBase")
    val primaryGlow by animateColorAsState(palette.primaryGlow, colorSpec, label = "primaryGlow")
    val secondaryGlow by animateColorAsState(palette.secondaryGlow, colorSpec, label = "secondaryGlow")
    val accentGlow by animateColorAsState(palette.accentGlow, colorSpec, label = "accentGlow")

    val smoothBeat by animateFloatAsState(
        targetValue = beatIntensity.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 240f),
        label = "beatGlow",
    )
    val smoothAmp by animateFloatAsState(
        targetValue = audioAmplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 180f),
        label = "ampGlow",
    )

    val driftTransition = rememberInfiniteTransition(label = "liquidMeshDrift")
    val phaseA by driftTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 19_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phaseA",
    )
    val phaseB by driftTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 27_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phaseB",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val minDim = minOf(w, h)
        val beatBoost = 1f + (smoothBeat * 0.14f) + (smoothAmp * 0.08f)

        // Deep base canvas
        drawRect(color = deepBase)

        // Primary luminous fluid orb (upper-left drifting)
        val center1 = Offset(
            x = w * (0.24f + 0.14f * cos(phaseA)),
            y = h * (0.22f + 0.10f * sin(phaseA * 0.8f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryGlow.copy(alpha = (0.58f + smoothBeat * 0.16f).coerceAtMost(0.82f)),
                    primaryGlow.copy(alpha = 0.18f),
                    Color.Transparent,
                ),
                center = center1,
                radius = minDim * 0.92f * beatBoost,
            ),
            radius = minDim * 0.92f * beatBoost,
            center = center1,
        )

        // Secondary luminous fluid orb (mid-right drifting)
        val center2 = Offset(
            x = w * (0.78f + 0.13f * sin(phaseB)),
            y = h * (0.54f + 0.14f * cos(phaseB * 0.7f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryGlow.copy(alpha = (0.52f + smoothAmp * 0.14f).coerceAtMost(0.76f)),
                    secondaryGlow.copy(alpha = 0.15f),
                    Color.Transparent,
                ),
                center = center2,
                radius = minDim * 0.88f * beatBoost,
            ),
            radius = minDim * 0.88f * beatBoost,
            center = center2,
        )

        // Tertiary luminous accent orb (lower-left rising)
        val center3 = Offset(
            x = w * (0.32f + 0.18f * sin(phaseA + 1.4f)),
            y = h * (0.82f + 0.09f * cos(phaseB + 0.9f)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentGlow.copy(alpha = (0.44f + smoothBeat * 0.15f).coerceAtMost(0.70f)),
                    accentGlow.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                center = center3,
                radius = minDim * 0.78f * beatBoost,
            ),
            radius = minDim * 0.78f * beatBoost,
            center = center3,
        )

        // Subtle atmospheric vignette so foreground typography always stays ultra-legible
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.34f),
                    Color.Black.copy(alpha = 0.14f),
                    Color.Black.copy(alpha = 0.42f),
                ),
            ),
        )
    }
}
