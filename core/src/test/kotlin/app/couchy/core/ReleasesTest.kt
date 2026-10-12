package app.couchy.core

import app.couchy.core.sources.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReleasesTest {
    @Test
    fun earliestDigitalOverCinema() {
        val json = """{"results":[
            {"iso_3166_1":"US","release_dates":[{"type":3,"release_date":"2026-03-01T00:00:00.000Z"},{"type":4,"release_date":"2026-05-20T00:00:00.000Z"}]},
            {"iso_3166_1":"RU","release_dates":[{"type":4,"release_date":"2026-05-10T00:00:00.000Z"}]}]}"""
        assertEquals(Instant.parse("2026-05-10T00:00:00Z").toEpochMilli(), Releases.homeRelease(json))
    }

    @Test
    fun cinemaOnlyIsNotYetHome() {
        assertNull(Releases.homeRelease("""{"results":[{"release_dates":[{"type":3,"release_date":"2026-03-01T00:00:00.000Z"}]}]}"""))
        assertEquals(Instant.parse("2026-06-02T00:00:00Z").toEpochMilli(), Releases.cinemetaHome("""{"meta":{"dvdRelease":"2026-06-02T00:00:00.000Z"}}"""))
    }
}

class WatchProviderTest {
    @Test
    fun subscriptionFirstThenBuy() {
        val o = org.json.JSONObject("""{"results":{"RU":{"link":"https://www.themoviedb.org/movie/1/watch?locale=RU",
            "buy":[{"provider_name":"Okko"}],"flatrate":[{"provider_name":"Kinopoisk"},{"provider_name":"Okko"}]}}}""")
        val (list, link) = app.couchy.core.sources.WatchProvider.parse(o)
        assertEquals(listOf("Kinopoisk" to "по подписке", "Okko" to "по подписке"), list.map { it.name to it.how })
        assertEquals("https://www.themoviedb.org/movie/1/watch?locale=RU", link)
    }
}
