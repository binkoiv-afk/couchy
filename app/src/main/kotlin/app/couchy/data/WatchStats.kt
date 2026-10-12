package app.couchy.data

import android.content.Context
import app.couchy.core.Stats
import app.couchy.core.StatsPeriod
import app.couchy.core.StatsSummary
import app.couchy.core.Taste
import app.couchy.core.WatchDay
import java.time.LocalDate

/**
 * "Итоги": minutes actually played per title and day, plus the genres and cast of what was
 * played. Kept on the device, per profile.
 */
class WatchStats(context: Context) {
    private val prefs = context.getSharedPreferences("stats${Profiles.suffix(context)}", Context.MODE_PRIVATE)
    private val days = HashMap<Pair<Long, String>, Long>()
    private val taste = HashMap<String, Taste>()
    private var unsaved = 0L

    init {
        Stats.logFromJson(prefs.getString("log", null)).forEach { days[it.day to it.title] = it.ms }
        taste.putAll(Stats.tasteFromJson(prefs.getString("taste", null)))
    }

    /** Called while the player plays: [ms] more of [playbackTitle]. */
    @Synchronized
    fun played(playbackTitle: String, ms: Long) {
        val name = Stats.name(playbackTitle).ifBlank { return }
        val key = LocalDate.now().toEpochDay() to name
        days[key] = (days[key] ?: 0) + ms
        unsaved += ms
        if (unsaved >= 30_000) flush()
    }

    @Synchronized
    fun remember(name: String, genres: List<String>, actors: List<String>) {
        if (name.isBlank() || (genres.isEmpty() && actors.isEmpty())) return
        taste[Stats.name(name)] = Taste(genres, actors.take(Stats.ACTORS_PER_TITLE))
        prefs.edit().putString("taste", Stats.tasteToJson(taste)).apply()
    }

    @Synchronized
    fun flush() {
        unsaved = 0
        prefs.edit().putString("log", Stats.logToJson(days.map { (k, ms) -> WatchDay(k.first, k.second, ms) })).apply()
    }

    @Synchronized
    fun summary(period: StatsPeriod): StatsSummary =
        Stats.summarize(days.map { (k, ms) -> WatchDay(k.first, k.second, ms) }, taste, period, LocalDate.now().toEpochDay())
}
