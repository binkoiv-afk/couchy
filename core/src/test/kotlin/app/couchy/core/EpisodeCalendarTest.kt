package app.couchy.core

import app.couchy.core.sources.AiredEpisode
import app.couchy.core.sources.EpisodeCalendar
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class EpisodeCalendarTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L
    private val now = LocalDate.of(2026, 10, 14).atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L

    @Test
    fun groupsByDayAroundNow() {
        val eps = listOf(
            AiredEpisode(1, 1, now - 30 * day),
            AiredEpisode(1, 2, now - 2 * day),
            AiredEpisode(1, 3, now + day),
            AiredEpisode(1, 4, now + 90 * day),
        )
        val days = EpisodeCalendar.build(listOf(Triple("a", "Тьма", eps)), now, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15)), days.map { it.date })
        assertEquals("Завтра", days[1].label)
        assertEquals("пн, 12 октября", days[0].label)
    }

    @Test
    fun tags() {
        assertEquals("вышла", EpisodeCalendar.tag(AiredEpisode(1, 1, now - day), now, zone))
        assertEquals("через 3 дня", EpisodeCalendar.tag(AiredEpisode(1, 1, now + 3 * day), now, zone))
        assertEquals("через 5 дней", EpisodeCalendar.tag(AiredEpisode(1, 1, now + 5 * day), now, zone))
        assertEquals("через 1 день", EpisodeCalendar.tag(AiredEpisode(1, 1, now + day), now, zone))
    }
}
