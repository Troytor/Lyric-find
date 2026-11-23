package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val CadenceDarkColorScheme = darkColorScheme(
    primary = CadenceSky,
    onPrimary = CadenceObsidian,
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = CadenceViolet,
    onSecondary = CadenceObsidian,
    secondaryContainer = Color(0xFF2E1065),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = CadenceCyan,
    onTertiary = CadenceObsidian,
    background = CadenceObsidian,
    onBackground = CadencePearl,
    surface = CadenceSurface,
    onSurface = CadencePearl,
    surfaceVariant = CadenceSurfaceElevated,
    onSurfaceVariant = CadenceMist,
    outline = Color(0x44FFFFFF),
    outlineVariant = Color(0x22FFFFFF),
    error = CadenceCoral,
    onError = CadenceObsidian,
)

val CadenceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * First-party Material You dark-immersive theme for Cadence.
 * Uses Android 12+ dynamic dark color schemes seamlessly harmonized with liquid glass surfaces.
 */
@Composable
fun CadenceTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context).copy(
                background = CadenceObsidian,
                onBackground = CadencePearl,
                onSurface = CadencePearl,
            )
        }
        else -> CadenceDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CadenceTypography,
        shapes = CadenceShapes,
        content = content,
    )
}
