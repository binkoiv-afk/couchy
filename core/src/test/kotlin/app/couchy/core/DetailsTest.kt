package app.couchy.core

import app.couchy.core.sources.CatalogFilter
import app.couchy.core.sources.CatalogSort
import app.couchy.core.sources.CatalogType
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.RezkaCatalog
import app.couchy.core.sources.RezkaDetails
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleJson
import app.couchy.core.sources.TitleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("sources/$name")!!.readText()

    @Test fun titleDetails() {
        val d = Rezka.parsePage(res("rezka_details.html").replace("<table", "<input id=\"post_id\" value=\"57233\"><table")).details
        assertEquals("Long live the fighters", d.slogan)
        assertEquals("27 февраля 2024 года", d.released)
        assertEquals(listOf("США", "Канада"), d.countries.map { it.name })
        assertEquals(listOf("Фантастика", "Приключения"), d.genres.map { it.name })
        assertEquals("https://rezka.ag/films/fiction/", d.genres[0].url)
        assertEquals(listOf("Дени Вильнёв"), d.directors.map { it.name })
        assertEquals(listOf("Тимоти Шаламе", "Зендея"), d.actors.map { it.name })
        assertEquals("https://rezka.ag/person/2-zendeya/", d.actors[1].url)
        assertEquals("12+", d.age)
        assertEquals("166 мин.", d.duration)
        assertEquals(listOf("Дюна"), d.parts.map { it.name })
        assertEquals(2021, d.parts[0].year)
        assertEquals(listOf("Интерстеллар"), d.similar.map { it.name })
        assertEquals("https://static/i.jpg", d.similar[0].poster)
    }

    @Test fun looseLinks() {
        val html = """<div><a href="https://rezka.ag/series/drama/9-x-2020.html"><img src="p.jpg" alt="Икс"></a><a href="https://rezka.ag/series/drama/9-x-2020.html">Икс</a></div>"""
        val list = RezkaDetails.loose(html)
        assertEquals(1, list.size)
        assertEquals("Икс", list[0].name)
        assertEquals("p.jpg", list[0].poster)
        assertEquals(TitleKind.SERIES, list[0].kind)
    }

    @Test fun catalogPaths() {
        assertEquals("/films/fiction/?filter=popular", RezkaCatalog.path(CatalogFilter(genre = "fiction")))
        assertEquals("/series/page/3/?filter=watching", RezkaCatalog.path(CatalogFilter(CatalogType.SERIES, CatalogSort.WATCHING), 3))
        assertEquals("/films/best/drama/2023/", RezkaCatalog.path(CatalogFilter(genre = "drama", year = 2023)))
        assertEquals("/cartoons/best/page/2/", RezkaCatalog.path(CatalogFilter(CatalogType.CARTOONS, CatalogSort.BEST), 2))
        assertEquals("/country/%D0%9A%D0%BE%D1%80%D0%B5%D1%8F%20%D0%AE%D0%B6%D0%BD%D0%B0%D1%8F/", RezkaCatalog.path(CatalogFilter(country = "Корея Южная")))
        val f = CatalogFilter(CatalogType.SERIES, country = "США", year = 2020)
        assertTrue(RezkaCatalog.keep(f, Title("a", year = 2020, rezkaUrl = "https://rezka.ag/series/drama/1-a-2020.html")))
        assertFalse(RezkaCatalog.keep(f, Title("b", year = 2020, rezkaUrl = "https://rezka.ag/films/drama/2-b-2020.html")))
        assertFalse(RezkaCatalog.keep(f, Title("c", year = 2019, rezkaUrl = "https://rezka.ag/series/drama/3-c-2019.html")))
        assertEquals(CatalogFilter(CatalogType.SERIES, genre = "realtv", genreLabel = "Реальное ТВ"), RezkaCatalog.fromGenreLink(app.couchy.core.sources.Link("Реальное ТВ", "https://rezka.ag/series/realtv/")))
        assertEquals("Реальное ТВ · сериалы", RezkaCatalog.describe(CatalogFilter(CatalogType.SERIES, genre = "realtv", genreLabel = "Реальное ТВ")))
        assertEquals("Драмы · фильмы · 2023", RezkaCatalog.describe(CatalogFilter(genre = "drama", year = 2023)))
    }

    @Test fun titlesRoundTrip() {
        val list = listOf(Title("Дюна", "Dune", listOf("Dune", "Dune: Part One"), 2021, TitleKind.MOVIE, "p", "tt1", "u", "2021, США", "КП 8"), Title("X"))
        assertEquals(list, TitleJson.decode(TitleJson.encode(list)))
    }
}
