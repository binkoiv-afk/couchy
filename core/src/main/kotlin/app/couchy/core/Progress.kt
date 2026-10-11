package app.couchy.core

/** Rules for "continue watching": when to offer resuming and when a video counts as watched. */
object Progress {
    /** Less than this from the start is not worth resuming. */
    const val MIN_RESUME_MS = 30_000L

    /** Watched once this share is played, so credits don't leave a title half-finished forever. */
    const val WATCHED_SHARE = 0.92

    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * WATCHED_SHARE

    /** Where to start playback, or 0 to start from the beginning. */
    fun resumeFrom(positionMs: Long, durationMs: Long): Long = when {
        positionMs < MIN_RESUME_MS -> 0
        isWatched(positionMs, durationMs) -> 0
        else -> positionMs
    }

    /** Share played, 0..1, for progress bars; 0 when the duration is not known yet. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()

    /** "ещё 1 ч 22 мин" / "ещё 9 мин" for the time left. */
    fun remainingLabel(positionMs: Long, durationMs: Long): String? {
        if (durationMs <= 0) return null
        val minutes = ((durationMs - positionMs).coerceAtLeast(0) + 59_999) / 60_000
        return "ещё " + Time.humanMinutes(minutes)
    }
}

object Time {
    /** 51:22 or 1:02:05, the player clock. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** 1 ч 22 мин, 45 мин, 2 ч. */
    fun humanMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }
}
