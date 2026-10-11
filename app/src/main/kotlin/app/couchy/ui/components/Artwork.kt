package app.couchy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Artwork for titles that have no poster yet (files and links): a gradient picked from the
 * title, so every video keeps its own recognizable color.
 */
private val palettes = listOf(
    Triple(0xFF1B3B5F, 0xFF5FA8D3, 0xFF0B1B2B),
    Triple(0xFF7A2E12, 0xFFF08A4B, 0xFF2A0F05),
    Triple(0xFF0F3D3E, 0xFF2FD3B5, 0xFF04191A),
    Triple(0xFF3A0E5A, 0xFFE04ADB, 0xFF12031D),
    Triple(0xFF2D4A2B, 0xFFA6D785, 0xFF0E1A0D),
    Triple(0xFF1D2B4F, 0xFF9DB8F5, 0xFF0A0F1F),
    Triple(0xFF5A3B12, 0xFFE8B04B, 0xFF1F1405),
    Triple(0xFF4A1420, 0xFFFF6B6B, 0xFF1A0509),
    Triple(0xFF101D3A, 0xFF5A7BFF, 0xFF04081A),
)

data class ArtColors(val base: Color, val glow: Color, val deep: Color)

fun artColorsFor(key: String): ArtColors {
    val (a, b, c) = palettes[Math.floorMod(key.hashCode(), palettes.size)]
    return ArtColors(Color(a), Color(b), Color(c))
}

@Composable
fun Artwork(key: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val colors = remember(key) { artColorsFor(key) }
    Box(modifier.background(Brush.linearGradient(listOf(colors.base, colors.deep)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.glow.copy(alpha = 0.85f), Color.Transparent),
                    center = Offset(size.width * 0.72f, size.height * 0.15f),
                    radius = size.maxDimension * 0.7f,
                ),
                radius = size.maxDimension * 0.7f,
                center = Offset(size.width * 0.72f, size.height * 0.15f),
            )
            drawCircle(
                Color.White.copy(alpha = 0.12f),
                radius = size.minDimension * 0.28f,
                center = Offset(size.width * 0.78f, size.height * 0.3f),
            )
        }
        content()
    }
}
