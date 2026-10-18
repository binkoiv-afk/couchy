package app.couchy.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The picture's colours along its four edges. */
private data class Edges(val top: Color, val bottom: Color, val left: Color, val right: Color)

object Ambient {
    private fun prefs(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("ambient", true)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("ambient", on).apply()
}

/**
 * The glow around the picture, as in YouTube: where the video leaves black bars (an upright
 * phone, a wide film), they softly take the colours of the frame's edges. A tiny copy of the
 * frame is read about once a second, so it costs next to nothing.
 */
@OptIn(UnstableApi::class)
@Composable
fun AmbientGlow(view: PlayerView?, player: Player, enabled: Boolean, modifier: Modifier = Modifier) {
    var edges by remember(player) { mutableStateOf<Edges?>(null) }
    LaunchedEffect(view, player, enabled) {
        if (!enabled || view == null) { edges = null; return@LaunchedEffect }
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        while (true) {
            if (player.isPlaying || edges == null) {
                if (copy(view, bitmap)) edges = edgesOf(bitmap)
            }
            delay(900)
        }
    }
    val clear = Color.Black
    val e = edges
    val top by animateColorAsState(e?.top ?: clear, tween(900), label = "top")
    val bottom by animateColorAsState(e?.bottom ?: clear, tween(900), label = "bottom")
    val left by animateColorAsState(e?.left ?: clear, tween(900), label = "left")
    val right by animateColorAsState(e?.right ?: clear, tween(900), label = "right")
    if (!enabled) return
    Canvas(modifier) {
        val video = player.videoSize
        if (video.width <= 0 || video.height <= 0) return@Canvas
        val aspect = video.width * video.pixelWidthHeightRatio / video.height
        val screen = size.width / size.height
        if (aspect >= screen) {
            // Bars above and below.
            val h = size.width / aspect
            val bar = (size.height - h) / 2
            if (bar < 4f) return@Canvas
            drawRect(Brush.verticalGradient(listOf(Color.Black, top.copy(alpha = 0.6f)), startY = 0f, endY = bar), Offset.Zero, Size(size.width, bar))
            drawRect(Brush.verticalGradient(listOf(bottom.copy(alpha = 0.6f), Color.Black), startY = bar + h, endY = size.height), Offset(0f, bar + h), Size(size.width, bar))
        } else {
            // Bars on the sides.
            val w = size.height * aspect
            val bar = (size.width - w) / 2
            if (bar < 4f) return@Canvas
            drawRect(Brush.horizontalGradient(listOf(Color.Black, left.copy(alpha = 0.6f)), startX = 0f, endX = bar), Offset.Zero, Size(bar, size.height))
            drawRect(Brush.horizontalGradient(listOf(right.copy(alpha = 0.6f), Color.Black), startX = bar + w, endX = size.width), Offset(bar + w, 0f), Size(bar, size.height))
        }
    }
}

/** A small copy of the current frame into [bitmap]; false when the picture cannot be read. */
private suspend fun copy(view: PlayerView, bitmap: Bitmap): Boolean = when (val surface = view.videoSurfaceView) {
    is TextureView -> runCatching { surface.getBitmap(bitmap) != null }.getOrDefault(false)
    is SurfaceView -> if (Build.VERSION.SDK_INT >= 24 && surface.holder.surface?.isValid == true) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(surface, bitmap, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    } else false
    else -> false
}

private fun edgesOf(b: Bitmap): Edges {
    fun avg(xs: IntRange, ys: IntRange): Color {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (x in xs) for (y in ys) {
            val p = b.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        if (n == 0) return Color.Black
        // A little brighter and richer, so a dim frame still glows.
        val boost = 1.25f
        return Color(((r / n) * boost / 255f).coerceAtMost(1f), ((g / n) * boost / 255f).coerceAtMost(1f), ((bl / n) * boost / 255f).coerceAtMost(1f))
    }
    val w = b.width
    val h = b.height
    return Edges(
        top = avg(0 until w, 0 until 3),
        bottom = avg(0 until w, h - 3 until h),
        left = avg(0 until 4, 0 until h),
        right = avg(w - 4 until w, 0 until h),
    )
}
