package app.couchy.core

import app.couchy.core.sources.Anilibria
import app.couchy.core.sources.KinoPub
import app.couchy.core.sources.NativeRef
import app.couchy.core.sources.matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTest {
    private fun res(name: String) = javaClass.getResource("/sources/$name")!!.readText()

    @Test fun anilibriaSearch() {
        val hits = Anilibria.parseSearch(res("anilibria_search.json"))
        val r = hits.matching(listOf("Dandadan"), 2024, null)!!
        assertEquals("dandadan", r.id)
        assertEquals("Дандадан", r.name)
        assertTrue(r.series)
        assertTrue(r.poster!!.startsWith("https://anilibria.top/storage/"))
    }

    @Test fun anilibriaRelease() {
        val r = Anilibria.parseRelease(res("anilibria_release.json"))
        assertEquals(3, r.episodes.size)
        val e = r.episodes[0]
        assertEquals(1, e.season)
        assertEquals(1, e.episode)
        assertEquals("1080p", e.files[0].first)
        assertEquals(Triple(6000L, 90000L, "Заставка"), e.skips[0])
    }

    @Test fun kinopubItem() {
        val json = """{"item":{"id":123,"title":"Дюна / Dune","year":2021,"type":"movie","imdb":1160419,
            "posters":{"medium":"https://p/m.jpg"},
            "videos":[{"number":1,"files":[{"quality":"720p","url":{"hls4":"https://v/720.m3u8"}},{"quality":"1080p","url":{"hls4":"https://v/1080.m3u8"}}]}]}}"""
        val r = KinoPub.parseItem(json)
        assertEquals("tt1160419", r.imdbId)
        assertEquals("Dune", r.original)
        assertEquals(false, r.series)
        assertEquals("1080p", r.episodes.single().files[0].first)
        assertEquals(r, listOf(r).matching(listOf("Другое"), null, "tt1160419"))
    }

    @Test fun kinopubSeries() {
        val json = """{"item":{"id":9,"title":"Сёгун / Shōgun","year":2024,"type":"serial","seasons":[{"number":1,"episodes":[
            {"number":1,"title":"Анджин","files":[{"quality":"1080p","url":{"hls4":"https://v/1.m3u8"}}]},
            {"number":2,"title":"","files":[{"quality":"1080p","url":{"hls4":"https://v/2.m3u8"}}]}]}]}}"""
        val r = KinoPub.parseItem(json)
        assertTrue(r.series)
        assertEquals(2, r.episodes.size)
        assertEquals("Серия 2", r.episodes[1].name)
    }

    @Test fun refs() {
        val ref = NativeRef.of("anilibria", "dandadan", 1, 3)
        assertTrue(NativeRef.isRef(ref))
        assertEquals(NativeRef.Ref("anilibria", "dandadan", 1, 3), NativeRef.parse(ref))
        assertNull(NativeRef.parse("https://x"))
        assertNull(KinoPub.parseTokens("""{"error":"authorization_pending"}""", 0))
    }
}
