package app.couchy.core.sources

import org.json.JSONObject

/** What one episode was about, for «Ранее в сериале». */
data class EpisodeNote(val season: Int, val episode: Int, val name: String?, val overview: String)

/**
 * «Ранее в сериале»: back to a series after a break, the episode before the one starting is
 * retold in a few lines.
 */
object Recap {
    /** A break this long or longer earns a recap. */
    const val GAP_MS = 3L * 24 * 3600 * 1000

    fun needed(lastWatchedElsewhere: Long?, now: Long): Boolean =
        lastWatchedElsewhere != null && lastWatchedElsewhere > 0 && now - lastWatchedElsewhere >= GAP_MS

    /** The episode just before season [season] episode [episode] that has something to tell. */
    fun previous(notes: List<EpisodeNote>, season: Int, episode: Int): EpisodeNote? =
        notes.filter { it.season > 0 && (it.season < season || it.season == season && it.episode < episode) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .lastOrNull()
            ?.takeIf { it.overview.isNotBlank() }

    /** TMDB's /tv/{id}/season/{n}. */
    fun parseTmdbSeason(json: String): List<EpisodeNote> {
        val a = JSONObject(json).optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            EpisodeNote(o.optInt("season_number"), o.optInt("episode_number"), o.optString("name").ifBlank { null }, o.optString("overview").trim())
        }
    }

    /** The `videos` of a Cinemeta series page; their texts are in English. */
    fun parseCinemeta(json: String): List<EpisodeNote> {
        val a = JSONObject(json).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            EpisodeNote(
                o.optInt("season"),
                o.optInt("episode", o.optInt("number")),
                o.optString("name").ifBlank { o.optString("title") }.ifBlank { null },
                o.optString("overview").ifBlank { o.optString("description") }.trim(),
            )
        }
    }
}
