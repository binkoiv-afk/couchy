package app.couchy.core

import app.couchy.core.sources.Imdb
import app.couchy.core.sources.Rezka
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TitleMerge
import app.couchy.core.sources.TorrentHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SourcesTest {
    private fun res(name: String) = javaClass.getResource("/sources/$name")!!.readText()

    @Test fun imdb() {
        val titles = Imdb.parse(res("imdb.json"))
        assertEquals("Dune", titles[0].name)
        assertEquals("tt1160419", titles[0].imdbId)
        assertEquals(2021, titles[0].year)
        assertTrue(titles[0].poster!!.endsWith("._V1_QL75_UX400_.jpg"))
        assertEquals("https://v3.sg.media-imdb.com/suggestion/x/%D0%B4%D1%8E%D0%BD%D0%B0.json", Imdb.suggestUrl("Дюна"))
        assertEquals("https://v3.sg.media-imdb.com/suggestion/b/breaking%20bad.json", Imdb.suggestUrl("Breaking Bad"))
    }

    @Test fun rezkaSearchAndMerge() {
        val site = Rezka.parseSearch(res("rezka_search.html"))
        assertEquals(4, site.size)
        assertEquals("Дюна: Часть вторая", site[0].name)
        assertEquals(2024, site[0].year)
        assertEquals("2024, США, Фантастика", site[0].info)
        assertTrue(site[0].poster!!.startsWith("https://static."))
        assertEquals(TitleKind.SERIES, site[3].kind)

        val quick = Rezka.parseQuickSearch(res("rezka_quick.html"))
        val (names, rating) = quick.getValue("https://rezka.ag/films/fiction/57233-dyuna-chast-vtoraya-2024.html")
        assertEquals(listOf("Dune: Part Two", "Dune 2"), names)
        assertEquals("8.17", rating)
        assertEquals(listOf("Dune: Prophecy"), quick.getValue("https://rezka.ag/series/fiction/74658-dyuna-prorochestvo-2024.html").first)

        val withNames = site.map { t -> quick[t.rezkaUrl]?.let { (n, _) -> t.copy(original = n.firstOrNull(), altNames = n) } ?: t }
        val merged = TitleMerge.merge(withNames, Imdb.parse(res("imdb.json")))
        assertEquals("tt15239678", merged[0].imdbId)
        assertEquals("Дюна: Часть вторая", merged[0].name)
        // "Дюна" (2021) is "Dune: Part One" on the site and "Dune" on IMDb; it stays unmatched,
        // and IMDb's entry follows the site's results.
        assertTrue(merged.drop(site.size).any { it.imdbId == "tt1160419" })
    }

    @Test fun rezkaPage() {
        val page = Rezka.parsePage(res("rezka_page.html"))
        assertEquals(646, page.id)
        assertTrue(page.series)
        assertEquals("Во все тяжкие", page.name)
        assertEquals("Breaking Bad", page.original)
        assertEquals("https://static.example/big.jpg", page.poster)
        assertEquals("IMDb 9.5", page.rating)
        assertEquals("Школьный учитель химии «Уолтер» узнаёт диагноз.", page.description)
        assertEquals(listOf(56, 111, 238), page.translators.map { it.id })
        assertEquals("Оригинал · Английский", page.translators[2].name)
        assertTrue(page.translators[1].ads)
        assertEquals(111, page.defaultTranslator)
        assertEquals(3, page.episodes.size)
        assertEquals(2, page.episodes[2].season)
        assertTrue(Rezka.isChallenge("<script id=\"anubis_challenge\" type=\"application/json\">"))
        assertFalse(Rezka.isChallenge(res("rezka_page.html")))
    }

    @Test fun rezkaStreams() {
        val plain = "[360p]https://cdn/a/360.mp4:hls:manifest.m3u8 or https://cdn/a/360.mp4,[1080p Ultra]https://cdn/a/1080.mp4:hls:manifest.m3u8 or https://cdn/a/1080.mp4"
        val b64 = Base64.getEncoder().encodeToString(plain.toByteArray())
        val junk = Base64.getEncoder().encodeToString("@#!".toByteArray())
        val encoded = "#h" + b64.substring(0, 20) + "//_//" + junk + b64.substring(20, 41) + "//_//" + Base64.getEncoder().encodeToString("^$".toByteArray()) + b64.substring(41)
        assertEquals(plain, Rezka.decode(encoded))
        val json = """{"success":true,"url":"$encoded","subtitle":"[Русский]https://cdn/s/ru.vtt,[English]https://cdn/s/en.vtt"}"""
        val stream = Rezka.parseStreamAnswer(json)
        assertEquals(listOf("360p", "1080p Ultra"), stream.options.map { it.quality })
        assertEquals("https://cdn/a/1080.mp4", stream.options.maxBy { it.rank }.url)
        assertEquals(listOf("Русский", "English"), stream.subtitles.map { it.label })
        val noSubs = Rezka.parseStreamAnswer("""{"success":true,"url":"$encoded","subtitle":false}""")
        assertTrue(noSubs.subtitles.isEmpty())
    }

    @Test fun rezkaRefs() {
        val ref = Rezka.ref("https://rezka.ag/series/drama/646-breaking-bad-2008.html", 111, 2, 5)
        assertTrue(Rezka.isRef(ref))
        assertEquals(Rezka.Ref("https://rezka.ag/series/drama/646-breaking-bad-2008.html", 111, 2, 5), Rezka.parseRef(ref))
        assertNull(Rezka.parseRef("https://x/y.html"))
        assertEquals(646, Rezka.idFromUrl(ref))
        assertEquals("https://hdrezka.me/series/drama/646-breaking-bad-2008.html", Rezka.onHost("https://rezka.ag/series/drama/646-breaking-bad-2008.html", "https://hdrezka.me/"))
        assertEquals(LinkKind.UNKNOWN, Links.classify("https://rezka.ag/films/x/1-a.html"))
    }

    @Test fun torrents() {
        val jac = TorrentHits.parseJacred(res("jacred.json"))
        assertEquals(6, jac.size)
        assertEquals("nnmclub", jac[0].tracker)
        assertEquals(1080, jac[0].quality)
        assertTrue(jac[0].magnet.startsWith("magnet:?xt=urn:btih:"))
        val tio = TorrentHits.parseTorrentio(res("torrentio.json"))
        assertEquals(6, tio.size)
        assertEquals("YTS", tio[0].tracker)
        assertEquals(1090, tio[0].seeds)
        assertEquals(1080, tio[0].quality)
        assertEquals(2160, tio[1].quality)
        assertTrue(tio[1].hdr)
        assertEquals((2.76 * (1L shl 30)).toLong(), tio[0].size)
        val groups = TorrentHits.group(jac + tio + tio.take(1).map { it.copy(seeds = 5) })
        assertEquals("4K", groups[0].label)
        assertEquals(1, groups.flatMap { it.hits }.count { it.hash == tio[0].hash })
        val hd = groups.first { it.label == "1080p" }.hits
        assertEquals(hd.sortedByDescending { it.seeds }, hd)
    }
}
