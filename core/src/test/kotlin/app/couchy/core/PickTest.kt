package app.couchy.core

import app.couchy.core.sources.Pick
import app.couchy.core.sources.PickLength
import app.couchy.core.sources.PickMood
import app.couchy.core.sources.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PickTest {
    @Test
    fun shortComedyAsksForShortWellRatedFilms() {
        val (path, p) = Pick.tmdb(PickMood.LAUGH, PickLength.SHORT, 3)
        assertEquals("/discover/movie", path)
        assertEquals("35", p["with_genres"])
        assertEquals("100", p["with_runtime.lte"])
        assertEquals("3", p["page"])
        assertEquals("16,99", p["without_genres"])
    }

    @Test
    fun seriesUseTvGenresAndFamilyKeepsCartoons() {
        val (path, p) = Pick.tmdb(PickMood.FAMILY, PickLength.SERIES, 1)
        assertEquals("/discover/tv", path)
        assertFalse("without_genres" in p)
        assertFalse("with_runtime.lte" in p)
    }

    @Test
    fun choosesThreeUnseen() {
        val pool = (1..6).map { Title("T$it", year = 2000 + it, poster = "p") }
        val seen = setOf(pool[0].key, pool[1].key)
        val picked = Pick.choose(pool, seen, Random(1))
        assertEquals(3, picked.size)
        assertTrue(picked.none { it.key in seen })
    }
}
