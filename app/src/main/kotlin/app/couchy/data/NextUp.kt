package app.couchy.data

import android.content.Context
import app.couchy.core.sources.NativeRef
import app.couchy.core.sources.Rezka

/**
 * Which episode comes after which, remembered while a series plays, so «Продолжить» can offer
 * the next episode once one is finished instead of dropping the series.
 */
object NextUp {
    private const val PREFS = "next_up"

    fun remember(context: Context, key: String, nextRef: String?) {
        if (nextRef == null || episodeOf(nextRef) == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, nextRef).apply()
    }

    /** The card for the episode after [finished]; null when it is not known. */
    fun after(context: Context, finished: MediaEntity, all: List<MediaEntity>): MediaEntity? {
        val ref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(finished.uri, null) ?: return null
        val (season, episode) = episodeOf(ref) ?: return null
        all.firstOrNull { it.uri == ref }?.let {
            // Already watched too: nothing to offer.
            if (app.couchy.core.Progress.isWatched(it.positionMs, it.durationMs)) return null
            return it.copy(lastWatchedAt = finished.lastWatchedAt, poster = it.poster ?: finished.poster)
        }
        val name = finished.title.substringBefore(" · ")
        return MediaEntity(
            uri = ref,
            title = "$name · S${season}E$episode",
            kind = finished.kind,
            addedAt = finished.addedAt,
            lastWatchedAt = finished.lastWatchedAt,
            kids = finished.kids,
            poster = finished.poster,
        )
    }

    /** Removing a card from «Продолжить»: the series stops offering this episode as the next one. */
    fun forget(context: Context, ref: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = prefs.all.filter { (k, v) -> k == ref || v == ref }.keys
        if (keys.isNotEmpty()) prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    private fun episodeOf(ref: String): Pair<Int, Int>? =
        Rezka.parseRef(ref)?.takeIf { it.season > 0 }?.let { it.season to it.episode }
            ?: NativeRef.parse(ref)?.let { it.season to it.episode }
}
