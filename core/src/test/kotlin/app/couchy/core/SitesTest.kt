package app.couchy.core

import app.couchy.core.sources.SiteSearch
import app.couchy.core.sources.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesTest {
    private fun res(name: String) = javaClass.getResource("/sources/$name")!!.readText()

    @Test fun kinogo() {
        val hits = SiteSearch.parse("kinogo", res("site_kinogo.html"), "https://kinogo.so")
        val dune = hits.first { it.name == "Дюна: Пророчество" }
        assertEquals(2024, dune.year)
        assertEquals("https://kinogo.so/serials/10031-djuna-prorochestvo-2024/", dune.url)
        assertTrue(dune.poster!!.startsWith("https://kinogo.so/uploads/"))
    }

    @Test fun gidonline() {
        val hits = SiteSearch.parse("gidonline", res("site_gidonline.html"), "https://gidonline.eu")
        val dune = hits.first { it.name == "Дюна: Пророчество" }
        assertEquals(2024, dune.year)
        assertEquals("https://gidonline.eu/17054-djuna-prorochestvo.html", dune.url)
        assertTrue(dune.poster!!.startsWith("https://gidonline.eu/uploads/"))
    }

    @Test fun kinokrad() {
        val hits = SiteSearch.parse("kinokrad", res("site_kinokrad.html"), "https://kinokrad.cc")
        assertEquals("Дюна: Пророчество", hits[0].name)
        assertEquals(2024, hits[0].year)
        assertEquals("https://kinokrad.cc/uploads/posts/2024-05/2063067.webp", hits[0].poster)
    }

    @Test fun baskino() {
        val hits = SiteSearch.parse("baskino", res("site_baskino.html"), "https://baskino.me")
        assertEquals("Дюна: Часть вторая", hits[0].name)
        assertEquals(2024, hits[0].year)
        assertEquals("https://baskino.me/films/41164-djuna-chast-vtoraja.html", hits[0].url)
        assertNotNull(SiteSearch.match(hits, listOf("Дюна: часть вторая"), 2024))
    }

    @Test fun seasonvar() {
        val hits = SiteSearch.parse("seasonvar", res("site_seasonvar.html"), "https://seasonvar.ru")
        assertTrue(hits.isNotEmpty())
        assertEquals("Долгая дорога в дюнах", hits[0].name)
        assertEquals("https://seasonvar.ru/serial-7211-Dolgaya_doroga_v_dyunah_psdpnrc.html", hits[0].url)
    }

    @Test fun streamsAndAds() {
        assertTrue(Sites.isVideo("https://cdn.example/hls/abc/master.m3u8?token=1"))
        assertTrue(Sites.isVideo("https://s1.cdn/x/720.mp4"))
        assertFalse(Sites.isVideo("https://ads.example/vast/preroll.mp4"))
        assertFalse(Sites.isVideo("https://site/player.js"))
        assertTrue(Sites.isAd("https://an.yandex.ru/meta/1"))
        assertEquals("https://c/master.m3u8", Sites.preferred(listOf("https://c/720/index.m3u8", "https://c/master.m3u8")))
        assertEquals("https://filmix.gg/search/%D0%B4%D1%8E%D0%BD%D0%B0", Sites.all.first { it.id == "filmix" }.searchUrl("дюна"))
    }

    @Test fun refs() {
        val ref = app.couchy.core.sources.SiteRef.of("https://kinokrad.cc/473232-dune.html", "kinokrad")
        assertTrue(app.couchy.core.sources.SiteRef.isRef(ref))
        assertEquals("https://kinokrad.cc/473232-dune.html" to "kinokrad", app.couchy.core.sources.SiteRef.parse(ref))
        assertEquals("zona", app.couchy.core.sources.SiteRef.siteOf("https://w140.zona.plus/movies/dune")?.id)
        assertEquals(null, app.couchy.core.sources.SiteRef.siteOf("https://example.com/x"))
    }
}
