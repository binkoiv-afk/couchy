package app.couchy.core

import app.couchy.core.sources.EpisodeNote
import app.couchy.core.sources.Recap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapTest {
    private val notes = listOf(
        EpisodeNote(1, 1, "Пилот", "Начало."),
        EpisodeNote(1, 2, null, "Середина."),
        EpisodeNote(2, 1, null, "Новый сезон."),
    )

    @Test
    fun previousCrossesSeasons() {
        assertEquals(1 to 2, Recap.previous(notes, 2, 1)?.let { it.season to it.episode })
        assertEquals("Начало.", Recap.previous(notes, 1, 2)?.overview)
        assertNull(Recap.previous(notes, 1, 1))
    }

    @Test
    fun onlyAfterABreak() {
        val day = 24 * 3600 * 1000L
        assertTrue(Recap.needed(0L + 1, 4 * day))
        assertFalse(Recap.needed(3 * day, 4 * day))
        assertFalse(Recap.needed(null, 4 * day))
    }

    @Test
    fun readsTmdbSeason() {
        val list = Recap.parseTmdbSeason("""{"episodes":[{"season_number":1,"episode_number":3,"name":"Х","overview":" Текст "}]}""")
        assertEquals(EpisodeNote(1, 3, "Х", "Текст"), list.single())
    }
}
