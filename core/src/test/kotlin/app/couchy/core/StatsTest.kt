package app.couchy.core

import org.junit.Test
import org.junit.Assert.assertEquals

class StatsTest {
    private val hour = 3_600_000L

    @Test
    fun countsOnlyThePeriodAndWeighsGenresByTime() {
        val log = listOf(
            WatchDay(100, "Тьма", 3 * hour),
            WatchDay(99, "Дюна", 2 * hour),
            WatchDay(10, "Старое", 50 * hour),
        )
        val taste = mapOf(
            "Тьма" to Taste(listOf("Драма", "Фантастика"), listOf("Луис Хофманн")),
            "Дюна" to Taste(listOf("Фантастика"), listOf("Тимоти Шаламе")),
            "Старое" to Taste(listOf("Комедия"), emptyList()),
        )
        val month = Stats.summarize(log, taste, StatsPeriod.MONTH, today = 100)
        assertEquals(5 * hour, month.ms)
        assertEquals(2, month.activeDays)
        assertEquals(listOf("Тьма", "Дюна"), month.titles.map { it.first })
        assertEquals("Фантастика" to 5 * hour, month.genres.first())
        assertEquals(55 * hour, Stats.summarize(log, taste, StatsPeriod.ALL, today = 100).ms)
    }

    @Test
    fun episodesCountTowardTheirSeries() {
        assertEquals("Тьма", Stats.name("Тьма · 1 сезон · 3 серия"))
        assertEquals("12 ч 5 мин", Stats.duration(12 * hour + 5 * 60_000))
        assertEquals("40 мин", Stats.duration(40 * 60_000))
    }

    @Test
    fun survivesJson() {
        val log = listOf(WatchDay(5, "Дюна", 42))
        assertEquals(log, Stats.logFromJson(Stats.logToJson(log)))
        val taste = mapOf("Дюна" to Taste(listOf("Фантастика"), listOf("Зендея")))
        assertEquals(taste, Stats.tasteFromJson(Stats.tasteToJson(taste)))
    }
}
