package app.couchy.core

import app.couchy.core.sources.Kinopoisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KinopoiskTest {
    @Test
    fun findsByImdb() {
        assertEquals(447301, Kinopoisk.parseByImdb("""{"total":1,"items":[{"kinopoiskId":447301,"imdbId":"tt1375666"}]}"""))
        assertNull(Kinopoisk.parseByImdb("""{"total":0,"items":[]}"""))
    }

    @Test
    fun searchPicksTheYear() {
        val json = """{"films":[{"filmId":1,"nameRu":"Дюна","year":"1984"},{"filmId":2,"nameRu":"Дюна","year":"2021"}]}"""
        assertEquals(2, Kinopoisk.parseSearch(json, 2021))
        assertEquals(2, Kinopoisk.parseSearch(json, 2020))
        assertEquals(1, Kinopoisk.parseSearch(json, null))
        assertNull(Kinopoisk.parseSearch(json, 2000))
    }

    @Test
    fun film() {
        val f = Kinopoisk.parseFilm("""{"kinopoiskId":447301,"ratingKinopoisk":8.66,"ratingKinopoiskVoteCount":900000,"description":"Кобб — талантливый вор","shortDescription":null}""")!!
        assertEquals("КП 8.7", f.chip)
        assertEquals("Кобб — талантливый вор", f.description)
        assertNull(Kinopoisk.parseFilm("""{"kinopoiskId":5,"ratingKinopoisk":null,"description":null,"shortDescription":"Коротко"}""")!!.chip)
        assertEquals("Коротко", Kinopoisk.parseFilm("""{"kinopoiskId":5,"description":null,"shortDescription":"Коротко"}""")!!.description)
    }
}
