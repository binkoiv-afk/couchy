package app.couchy.core.player

import kotlin.math.abs
import kotlin.math.roundToInt

/** One way the screen can run: its size and how many times a second it redraws. */
data class ScreenMode(val id: Int, val width: Int, val height: Int, val refresh: Float)

/**
 * The screen mode a film plays smoothest in: the same size as now, redrawing a whole number of
 * times per film frame (24 → 24 Hz, 25 → 50 Hz, 23.976 → 23.976 Hz). Null when the current mode
 * is already right or nothing fits.
 */
object FrameRates {
    fun pick(fps: Float, modes: List<ScreenMode>, current: ScreenMode): ScreenMode? {
        if (fps < 10f || fps > 121f) return null
        fun miss(m: ScreenMode): Float {
            val k = (m.refresh / fps).roundToInt().coerceAtLeast(1)
            return abs(m.refresh - k * fps) / k
        }
        val fitting = modes.filter { it.width == current.width && it.height == current.height }
            .map { it to miss(it) }
            .filter { it.second <= 0.06f }
        if (fitting.isEmpty()) return null
        // Exact first, then the fewest repeats of each frame.
        val best = fitting.minWith(compareBy<Pair<ScreenMode, Float>> { if (it.second < 0.005f) 0 else 1 }.thenBy { it.second }.thenBy { it.first.refresh }).first
        if (abs(best.refresh - current.refresh) < 0.005f) return null
        if (miss(current) <= miss(best) + 0.0001f) return null
        return best
    }
}
