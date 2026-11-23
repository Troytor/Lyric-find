package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.AmbientPalette

object ArtworkPaletteExtractor {

    fun extract(bitmap: Bitmap): AmbientPalette {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return AmbientPalette.DefaultIdle

        val stepX = (width / 12).coerceAtLeast(1)
        val stepY = (height / 12).coerceAtLeast(1)
        val hueBuckets = IntArray(12)
        val bucketBestColor = IntArray(12)
        val bucketBestScore = FloatArray(12)
        val hsv = FloatArray(3)

        var y = stepY / 2
        while (y < height) {
            var x = stepX / 2
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                if (AndroidColor.alpha(pixel) >= 200) {
                    AndroidColor.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value in 0.14f..0.95f) {
                        val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                        hueBuckets[bucket]++
                        val vividScore = (sat * 1.4f) + (value * 0.5f)
                        if (vividScore > bucketBestScore[bucket]) {
                            bucketBestScore[bucket] = vividScore
                            bucketBestColor[bucket] = pixel
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val ranked = (0 until 12)
            .filter { hueBuckets[it] > 0 }
            .sortedByDescending { hueBuckets[it] * (0.4f + bucketBestScore[it]) }

        if (ranked.isEmpty()) return AmbientPalette.DefaultIdle

        val primaryInt = bucketBestColor[ranked[0]]
        val secondaryInt = ranked.getOrNull(1)?.let { bucketBestColor[it] } ?: shiftHue(primaryInt, 42f)
        val accentInt = ranked.getOrNull(2)?.let { bucketBestColor[it] } ?: shiftHue(primaryInt, -55f)

        return AmbientPalette(
            deepBase = tuneColor(primaryInt, targetSat = 0.52f, targetVal = 0.11f),
            primaryGlow = tuneColor(primaryInt, targetSat = 0.72f, targetVal = 0.58f),
            secondaryGlow = tuneColor(secondaryInt, targetSat = 0.68f, targetVal = 0.50f),
            accentGlow = tuneColor(accentInt, targetSat = 0.74f, targetVal = 0.54f),
            highlightTint = tuneColor(primaryInt, targetSat = 0.45f, targetVal = 0.94f),
        )
    }

    private fun tuneColor(argb: Int, targetSat: Float, targetVal: Float): Color {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hsv[1] = ((hsv[1] + targetSat) * 0.5f).coerceIn(0.28f, 0.85f)
        hsv[2] = targetVal.coerceIn(0.08f, 0.96f)
        return Color(AndroidColor.HSVToColor(hsv))
    }

    private fun shiftHue(argb: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return AndroidColor.HSVToColor(hsv)
    }
}
