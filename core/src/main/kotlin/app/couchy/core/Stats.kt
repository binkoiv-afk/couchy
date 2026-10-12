package app.couchy.core

import org.json.JSONArray
import org.json.JSONObject

/** How long one title played on one day (days since 1970). */
data class WatchDay(val day: Long, val title: String, val ms: Long)

/** Genres and main cast of a title, remembered when it is played. */
data class Taste(val genres: List<String>, val actors: List<String>)

enum class StatsPeriod(val label: String, val days: Long?) {
    MONTH("Месяц", 30),
    YEAR("Год", 365),
    ALL("Всё время", null),
}

/** "Итоги": time watched and what it was spent on. Genres and actors are weighted by that time. */
data class StatsSummary(
    val ms: Long,
    val activeDays: Int,
    /** How many different films and series. */
    val count: Int,
    val titles: List<Pair<String, Long>>,
    val genres: List<Pair<String, Long>>,
    val actors: List<Pair<String, Long>>,
) {
    val isEmpty: Boolean get() = ms <= 0
}

object Stats {
    /** Main cast only: the long tail of a cast list says little about taste. */
    const val ACTORS_PER_TITLE = 6

    /** "Название · 1 сезон · 3 серия" → "Название": episodes count toward their series. */
    fun name(playbackTitle: String): String = playbackTitle.substringBefore(" · ").trim()

    fun summarize(log: List<WatchDay>, taste: Map<String, Taste>, period: StatsPeriod, today: Long, top: Int = 5): StatsSummary {
        val from = period.days?.let { today - it + 1 } ?: Long.MIN_VALUE
        val used = log.filter { it.day >= from && it.ms > 0 }
        val byTitle = used.groupBy { it.title }.mapValues { (_, d) -> d.sumOf { it.ms } }
        val genres = HashMap<String, Long>()
        val actors = HashMap<String, Long>()
        byTitle.forEach { (title, ms) ->
            val t = taste[title] ?: return@forEach
            t.genres.distinct().forEach { genres[it] = (genres[it] ?: 0) + ms }
            t.actors.take(ACTORS_PER_TITLE).distinct().forEach { actors[it] = (actors[it] ?: 0) + ms }
        }
        fun Map<String, Long>.best() = entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(top).map { it.key to it.value }
        return StatsSummary(
            ms = byTitle.values.sum(),
            activeDays = used.map { it.day }.distinct().size,
            count = byTitle.size,
            titles = byTitle.best(),
            genres = genres.best(),
            actors = actors.best(),
        )
    }

    /** "12 ч 40 мин", "35 мин". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    fun logToJson(log: List<WatchDay>): String = JSONArray().apply {
        log.forEach { put(JSONObject().put("d", it.day).put("t", it.title).put("ms", it.ms)) }
    }.toString()

    fun logFromJson(text: String?): List<WatchDay> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { WatchDay(it.getLong("d"), it.getString("t"), it.getLong("ms")) }
    }.getOrDefault(emptyList())

    fun tasteToJson(taste: Map<String, Taste>): String = JSONObject().apply {
        taste.forEach { (title, t) -> put(title, JSONObject().put("g", JSONArray(t.genres)).put("a", JSONArray(t.actors))) }
    }.toString()

    fun tasteFromJson(text: String?): Map<String, Taste> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val t = o.getJSONObject(k)
            fun list(key: String) = t.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Taste(list("g"), list("a"))
        }
    }.getOrDefault(emptyMap())
}
