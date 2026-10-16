package app.couchy.core

import app.couchy.core.sources.Pick
import app.couchy.core.sources.PickLength
import app.couchy.core.sources.PickMood
import app.couchy.core.sources.PickEra
import app.couchy.core.sources.PickOptions
import app.couchy.core.sources.PickOrigin
import app.couchy.core.sources.PickScore
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PickTest {
    @Test
    fun shortComedyAsksForShortWellRatedFilms() {
        val (path, p) = Pick.tmdb(PickOptions(PickMood.LAUGH, PickLength.SHORT), 3)
        assertEquals("/discover/movie", path)
        assertEquals("35", p["with_genres"])
        assertEquals("100", p["with_runtime.lte"])
        assertEquals("3", p["page"])
        assertEquals("16,99", p["without_genres"])
    }

    @Test
    fun seriesUseTvGenresAndFamilyKeepsCartoons() {
        val (path, p) = Pick.tmdb(PickOptions(PickMood.FAMILY, PickLength.SERIES), 1)
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

    @Test
    fun eraCountryAndScoreNarrowTheChoice() {
        val (_, p) = Pick.tmdb(PickOptions(era = PickEra.Y1990, origin = PickOrigin.OURS, score = PickScore.GREAT), 1, 2026)
        assertEquals("1990-01-01", p["primary_release_date.gte"])
        assertEquals("1999-12-31", p["primary_release_date.lte"])
        assertEquals("RU|SU", p["with_origin_country"])
        assertEquals("7.5", p["vote_average.gte"])
        val (_, n) = Pick.tmdb(PickOptions(era = PickEra.NEW, length = PickLength.SERIES), 1, 2026)
        assertEquals("2025-01-01", n["first_air_date.gte"])
        val (_, l) = Pick.tmdb(PickOptions(length = PickLength.LONG, lesserKnown = true), 1, 2026)
        assertEquals("140", l["with_runtime.gte"])
        assertEquals("vote_average.desc", l["sort_by"])
        assertTrue("vote_count.lte" in l)
        val (_, c) = Pick.tmdb(PickOptions(mood = PickMood.CARTOON), 1, 2026)
        assertFalse("without_genres" in c)
    }

    @Test
    fun tasteFillsUpToTwo() {
        val pool = (1..6).map { Title("T$it", year = 2000 + it, poster = "p") }
        val liked = listOf(Title("L1", year = 2010, poster = "p"), Title("L2", year = 2011, poster = "p"), Title("L3", year = 2012, poster = "p"))
        val picked = Pick.choose(pool, emptySet(), Random(2), liked)
        assertEquals(3, picked.size)
        assertEquals(2, picked.count { it.name.startsWith("L") })
        assertTrue(Pick.fits(Title("A", year = 1995), PickOptions(era = PickEra.Y1990)))
        assertFalse(Pick.fits(Title("A", year = 2005), PickOptions(era = PickEra.Y1990)))
        assertFalse(Pick.fits(Title("A", year = 1995, kind = TitleKind.SERIES), PickOptions()))
    }
}
