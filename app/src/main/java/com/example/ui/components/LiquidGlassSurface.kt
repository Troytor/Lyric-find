package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Multi-layered translucent liquid glass container with top-edge specular rim
 * and crisp Material You tonal sheen.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tintColor: Color = Color(0xFF0E172A),
    fillAlpha: Float = 0.42f,
    highlightAlpha: Float = 0.24f,
    borderWidth: Dp = 1.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val fillBrush = remember(tintColor, fillAlpha) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.14f),
                tintColor.copy(alpha = fillAlpha),
                tintColor.copy(alpha = (fillAlpha + 0.12f).coerceAtMost(0.85f)),
            ),
        )
    }
    val rimBrush = remember(highlightAlpha) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha * 1.35f),
                Color.White.copy(alpha = highlightAlpha * 0.35f),
                Color.White.copy(alpha = highlightAlpha * 0.72f),
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(fillBrush)
            .border(width = borderWidth, brush = rimBrush, shape = shape),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Tactile liquid glass circular icon button with spring compression and 48dp minimum target.
 */
@Composable
fun LiquidGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    accentColor: Color = Color(0xFF7DD3FC),
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.91f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 480f),
        label = "glassBtnScale",
    )

    val fillColor = if (isActive) accentColor.copy(alpha = 0.30f) else Color(0xFF10192E)
    val rimAlpha = if (isActive) 0.44f else 0.22f

    LiquidGlassSurface(
        shape = CircleShape,
        tintColor = fillColor,
        fillAlpha = if (isActive) 0.56f else 0.38f,
        highlightAlpha = rimAlpha,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Tactile liquid glass pill button with spring press physics and Material ripple.
 */
@Composable
fun LiquidGlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    accentColor: Color = Color(0xFF7DD3FC),
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 460f),
        label = "glassPillScale",
    )
    val shape = RoundedCornerShape(percent = 50)

    LiquidGlassSurface(
        shape = shape,
        tintColor = if (isActive) accentColor else Color(0xFF0E172A),
        fillAlpha = if (isActive) 0.34f else 0.42f,
        highlightAlpha = if (isActive) 0.46f else 0.24f,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
