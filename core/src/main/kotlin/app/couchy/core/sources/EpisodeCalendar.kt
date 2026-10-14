package app.couchy.core.sources

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One line of the episode calendar: which series, which episode, when. */
data class CalendarEntry<T>(val series: T, val name: String, val episode: AiredEpisode)

/** A day of the calendar with its heading: «Сегодня», «Завтра», «пт, 17 октября». */
data class CalendarDay<T>(val label: String, val date: LocalDate, val entries: List<CalendarEntry<T>>)

/**
 * The episodes of the followed series around now: the last week's, so nothing new is missed,
 * and the next two months', so the person knows what is coming and when.
 */
object EpisodeCalendar {
    private val ru = Locale.forLanguageTag("ru")

    fun <T> build(
        series: List<Triple<T, String, List<AiredEpisode>>>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        pastDays: Long = 7,
        aheadDays: Long = 60,
    ): List<CalendarDay<T>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val from = today.minusDays(pastDays)
        val to = today.plusDays(aheadDays)
        return series.flatMap { (s, name, eps) -> eps.map { CalendarEntry(s, name, it) } }
            .filter { e -> dateOf(e.episode, zone).let { !it.isBefore(from) && !it.isAfter(to) } }
            .groupBy { dateOf(it.episode, zone) }
            .toSortedMap()
            .map { (date, list) -> CalendarDay(label(date, today), date, list.sortedWith(compareBy({ it.name }, { it.episode.season }, { it.episode.episode }))) }
    }

    private fun dateOf(e: AiredEpisode, zone: ZoneId): LocalDate = Instant.ofEpochMilli(e.releasedMs).atZone(zone).toLocalDate()

    fun label(date: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "Сегодня"
        1L -> "Завтра"
        -1L -> "Вчера"
        else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, ru) + ", " + date.dayOfMonth + " " +
            date.month.getDisplayName(TextStyle.FULL, ru).let { genitive(it) }
    }

    /** "октябрь" → "октября": dates read in the genitive. */
    private fun genitive(month: String): String = when {
        month.endsWith("ь") || month.endsWith("й") -> month.dropLast(1) + "я"
        month.endsWith("т") -> month + "а"
        else -> month
    }

    /** "через 3 дня", "вышла" — the tag at the right of a line. */
    fun tag(e: AiredEpisode, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, dateOf(e, zone))
        return when {
            e.releasedMs <= now -> "вышла"
            days <= 0L -> "сегодня"
            else -> "через $days " + plural(days, "день", "дня", "дней")
        }
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
