package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.model.MicMode
import com.example.ui.theme.AmbientPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive liquid glass microphone orb with concentric fluid rings, live capture
 * progress arc, and a radial 16-band spectrum reacting in real time to microphone audio.
 */
@Composable
fun ListeningOrb(
    micMode: MicMode,
    amplitude: Float,
    spectrum: List<Float>,
    palette: AmbientPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    recordingProgress: Float = 0f,
) {
    val isRecording = micMode == MicMode.RECORDING_FINGERPRINT
    val isListening = isRecording || micMode == MicMode.IDENTIFYING
    val isMatching = micMode == MicMode.MATCHING_ONLINE
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val smoothAmp by animateFloatAsState(
        targetValue = if (isListening) amplitude.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 260f),
        label = "orbAmp",
    )
    val smoothProgress by animateFloatAsState(
        targetValue = if (isRecording) recordingProgress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "orbProgress",
    )
    val pressScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            isListening -> 1.04f + (smoothAmp * 0.08f)
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 360f),
        label = "orbScale",
    )

    val infinite = rememberInfiniteTransition(label = "orbIdlePulse")
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breathe",
    )
    val spinAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Box(
        modifier = modifier.size(272.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(272.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.31f

            // Expanding translucent wave rings while actively listening
            if (isListening || isMatching) {
                for (ring in 0 until 3) {
                    val phase = (breathe + ring * 0.33f) % 1f
                    val radius = baseRadius + (phase * size.minDimension * 0.21f) + (smoothAmp * 14f)
                    val alpha = (1f - phase) * (0.34f + smoothAmp * 0.24f)
                    drawCircle(
                        color = palette.highlightTint.copy(alpha = alpha.coerceIn(0f, 0.55f)),
                        radius = radius,
                        center = center,
                        style = Stroke(width = (2.2f - phase * 1.4f).dp.toPx()),
                    )
                }
            } else {
                val subtle = (sin(breathe * 2.0 * PI).toFloat() * 0.5f) + 0.5f
                drawCircle(
                    color = palette.highlightTint.copy(alpha = 0.14f + subtle * 0.12f),
                    radius = baseRadius * (1.16f + subtle * 0.05f),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }

            // 32-bar radial harmonic visualizer around the glass orb
            val barCount = 32
            for (i in 0 until barCount) {
                val angle = spinAngle + (i.toFloat() / barCount) * (2.0 * PI).toFloat()
                val bandValue = spectrum.getOrElse(i % spectrum.size.coerceAtLeast(1)) { 0f }
                val barLen = if (isListening) {
                    (6.dp.toPx()) + (bandValue * 32.dp.toPx())
                } else {
                    (4.dp.toPx()) + (sin(angle * 3f + spinAngle * 2f) * 2.5.dp.toPx())
                }
                val startR = baseRadius * 1.09f
                val endR = startR + barLen
                val start = Offset(
                    x = center.x + cos(angle) * startR,
                    y = center.y + sin(angle) * startR,
                )
                val end = Offset(
                    x = center.x + cos(angle) * endR,
                    y = center.y + sin(angle) * endR,
                )
                val barAlpha = if (isListening) {
                    (0.35f + bandValue * 0.65f).coerceIn(0.3f, 0.95f)
                } else {
                    0.22f
                }
                drawLine(
                    color = palette.highlightTint.copy(alpha = barAlpha),
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Luminous progress ring during the 5.5s audio fingerprint capture
            if (isRecording && smoothProgress > 0.01f) {
                val arcRadius = baseRadius * 1.03f
                val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                drawArc(
                    color = Color.White.copy(alpha = 0.16f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            palette.highlightTint,
                            Color.White,
                            palette.primaryGlow,
                            palette.highlightTint,
                        ),
                        center = center,
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * smoothProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Soft glowing glass core aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.primaryGlow.copy(alpha = if (isListening) 0.55f else 0.34f),
                        palette.secondaryGlow.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = baseRadius * 1.45f,
                ),
                radius = baseRadius * 1.45f,
                center = center,
            )
        }

        // Tactile center liquid glass sphere
        LiquidGlassSurface(
            shape = CircleShape,
            tintColor = if (isListening) palette.primaryGlow else Color(0xFF111C34),
            fillAlpha = if (isListening) 0.52f else 0.44f,
            highlightAlpha = if (isListening) 0.52f else 0.32f,
            borderWidth = 1.5.dp,
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .testTag("listen_orb_button")
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = Color.White),
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            when {
                isMatching -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.18f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp),
                    )
                }

                isListening -> {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Listening to music",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                }

                else -> {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Tap to identify song with microphone",
                        tint = Color.White,
                        modifier = Modifier.size(50.dp),
                    )
                }
            }
        }
    }
}
