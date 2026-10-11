package app.couchy.core

import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.Cinemeta
import app.couchy.core.sources.Countries
import app.couchy.core.sources.MetaLinks
import app.couchy.core.sources.Tmdb
import app.couchy.core.sources.TitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("sources/$name")!!.readText()

    @Test fun cinemetaMeta() {
        val m = Cinemeta.parseMeta(res("cinemeta_meta.json"))!!
        assertEquals("tt15239678", m.imdbId)
        assertEquals(693134, m.tmdbId)
        assertEquals("IMDb 8.4", m.rating)
        assertEquals(2024, m.year)
        assertEquals(listOf("Боевики", "Приключения", "Драмы"), m.details.genres.map { it.name })
        assertEquals("genre:action", m.details.genres[0].url)
        assertEquals("США", m.details.countries[0].name)
        assertEquals(listOf("Denis Villeneuve"), m.details.directors.map { it.name })
        assertEquals("name:Zendaya", m.details.actors[1].url)
        assertEquals("167 мин.", m.details.duration)
        assertEquals("1 марта 2024", m.details.released)
        assertTrue(m.backdrop!!.startsWith("https://"))
    }

    @Test fun cinemetaCatalog() {
        val list = Cinemeta.parseCatalog(res("cinemeta_catalog.json"), TitleKind.MOVIE)
        assertEquals(3, list.size)
        assertTrue(list.all { it.imdbId!!.startsWith("tt") && it.kind == TitleKind.MOVIE })
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/genre=Sci-Fi.json", Cinemeta.catalogUrl(CatalogFilter(genre = "fiction"), 1))
        assertEquals("https://v3-cinemeta.strem.io/catalog/series/imdbRating/genre=Drama&skip=50.json", Cinemeta.catalogUrl(CatalogFilter(CatalogType.SERIES, CatalogSort.BEST, genre = "drama"), 2))
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/year/genre=2023.json", Cinemeta.catalogUrl(CatalogFilter(year = 2023, genre = "drama"), 1))
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/genre=Animation.json", Cinemeta.catalogUrl(CatalogFilter(CatalogType.CARTOONS), 1))
    }

    @Test fun tmdbDetails() {
        val m = Tmdb.parseDetails(res("tmdb_movie.json"), TitleKind.MOVIE)
        assertEquals("Дюна: Часть вторая", m.name)
        assertEquals("Dune: Part Two", m.original)
        assertEquals("tt15239678", m.imdbId)
        assertEquals("TMDB 8.1", m.rating)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", m.backdrop)
        assertEquals("Долгой жизни бойцам", m.details.slogan)
        assertEquals(listOf("Фантастика", "Приключения"), m.details.genres.map { it.name })
        assertEquals(listOf("США", "Канада"), m.details.countries.map { it.name })
        assertEquals("tmdb:person:137427", m.details.directors.single().url)
        assertEquals(listOf("Тимоти Шаламе", "Зендея"), m.details.actors.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/t.jpg", m.details.photos[MetaLinks.tmdbPerson(1190668)])
        assertEquals("12+", m.details.age)
        assertEquals("166 мин.", m.details.duration)
        assertEquals("27 февраля 2024", m.details.released)
        assertEquals(438631, m.details.similar.single().tmdbId)
        assertEquals("abc", m.trailer)
    }

    @Test fun tmdbRequests() {
        val (url, headers) = Tmdb.request("k123", "/movie/1", mapOf("append_to_response" to "credits"))
        assertEquals("https://api.themoviedb.org/3/movie/1?append_to_response=credits&language=ru-RU&api_key=k123", url)
        assertTrue(headers.isEmpty())
        assertEquals("Bearer eyJabc", Tmdb.request("eyJabc", "/x").second["Authorization"])
        val (path, p) = Tmdb.discover(CatalogFilter(CatalogType.ANIME, CatalogSort.BEST, genre = "fantasy", country = "Япония"), 2)
        assertEquals("/discover/tv", path)
        assertEquals("10765,16", p["with_genres"])
        assertEquals("ja", p["with_original_language"])
        assertEquals("JP", p["with_origin_country"])
        assertEquals("2", p["page"])
        assertEquals("США", Countries.russian("US"))
    }
}
