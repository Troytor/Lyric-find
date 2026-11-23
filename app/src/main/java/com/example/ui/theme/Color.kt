package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Core Obsidian & Luminous Glass Dark Palette
val CadenceObsidian = Color(0xFF070B14)
val CadenceSurface = Color(0xFF0E1525)
val CadenceSurfaceElevated = Color(0xFF162036)
val CadenceCyan = Color(0xFF5EEAD4)
val CadenceSky = Color(0xFF60A5FA)
val CadenceViolet = Color(0xFFA78BFA)
val CadenceCoral = Color(0xFFFB7185)
val CadencePearl = Color(0xFFF8FAFC)
val CadenceMist = Color(0xFFCBD5E1)
val CadenceSlate = Color(0xFF64748B)

// Light fallback palette (when user/system explicitly uses light theme on non-stage overlays)
val CadenceLightPrimary = Color(0xFF2563EB)
val CadenceLightSecondary = Color(0xFF7C3AED)
val CadenceLightTertiary = Color(0xFF0D9488)
val CadenceLightBackground = Color(0xFFF8FAFC)
val CadenceLightSurface = Color(0xFFFFFFFF)

/**
 * Dynamic four-color liquid mesh palette extracted from real album artwork
 * or derived from Material You dynamic colors when idle.
 */
@Immutable
data class AmbientPalette(
    val deepBase: Color,
    val primaryGlow: Color,
    val secondaryGlow: Color,
    val accentGlow: Color,
    val highlightTint: Color,
) {
    companion object {
        val DefaultIdle = AmbientPalette(
            deepBase = Color(0xFF070C18),
            primaryGlow = Color(0xFF1D4ED8),
            secondaryGlow = Color(0xFF6D28D9),
            accentGlow = Color(0xFF0D9488),
            highlightTint = Color(0xFF7DD3FC),
        )
    }
}
