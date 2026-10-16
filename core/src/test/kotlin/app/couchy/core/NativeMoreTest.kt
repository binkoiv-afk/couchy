package app.couchy.core

import app.couchy.core.sources.Filmix
import app.couchy.core.sources.NativeRef
import app.couchy.core.sources.NativeVoice
import app.couchy.core.sources.Rutube
import app.couchy.core.sources.Seasonvar
import app.couchy.core.sources.matching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NativeMoreTest {
    @Test fun filmixMovie() {
        val json = """{"id":123,"title":"Дюна","original_title":"Dune","year":2021,"poster":"https://f.x/p.jpg",
            "player_links":{"movie":[
              {"translation":"Дубляж","link":"https://cdn.x/s/a/dune_[480,720,1080,].mp4"},
              {"translation":"Оригинал","link":"https://cdn.x/s/b/dune_[480,720,].mp4"}],"playlist":[]}}"""
        val r = Filmix.parseItem(json, null)
        assertEquals("123", r.id)
        assertEquals(listOf("Дубляж", "Оригинал"), r.voices)
        assertEquals(listOf("1080p", "720p", "480p"), r.episodes.single().files.map { it.first })
        assertEquals("https://cdn.x/s/a/dune_1080.mp4", r.episodes.single().files.first().second)
        val o = Filmix.parseItem(json, "Оригинал")
        assertEquals("https://cdn.x/s/b/dune_720.mp4", o.episodes.single().files.first().second)
        assertEquals("Оригинал", NativeVoice.voice(o.id))
        assertEquals("123", NativeVoice.base(o.id))
    }

    @Test fun filmixSeries() {
        val json = """{"id":7,"title":"Квест Вижна","original_title":"Vision Quest","year":2026,
            "player_links":{"movie":[],"playlist":{"1":{"LostFilm":{"1":{"link":"https://c/e1_%s.mp4","qualities":[480,720]},"2":{"link":"https://c/e2_%s.mp4","qualities":[480,720]}},
              "HDrezka Studio":{"1":{"link":"https://c/r1_%s.mp4","qualities":[1080]}}}}}}"""
        val r = Filmix.parseItem(json, "HDrezka Studio")
        assertTrue(r.series)
        assertEquals(setOf("LostFilm", "HDrezka Studio"), r.voices.toSet())
        assertEquals(1, r.episodes.size)
        assertEquals("https://c/r1_1080.mp4", r.episodes[0].files[0].second)
        val d = Filmix.parseItem(json, null)
        assertEquals(2, d.episodes.size)
        assertEquals("720p" to "https://c/e2_720.mp4", d.episodes[1].files[0])
        // A voiced id stays one piece in a library key.
        val ref = NativeRef.of(Filmix.ID, r.id, 1, 1)
        assertEquals(r.id, NativeRef.parse(ref)!!.id)
    }

    @Test fun filmixSearch() {
        val hits = Filmix.parseSearch("""[{"id":5,"title":"Дюна","original_title":"Dune","year":2021}]""")
        assertEquals("5", hits.matching(listOf("Dune"), 2021, null)!!.id)
    }

    @Test fun seasonvar() {
        val hits = Seasonvar.parseSearch("""{"query":"квест","suggestions":{"valu":["Квест Вижна / Vision Quest"]},"data":["serial-40123-Kvest_Vizhna.html"]}""")
        assertEquals("serial-40123-Kvest_Vizhna.html", hits.single().id)
        assertEquals("Vision Quest", hits.single().original)
        val html = """<title>Сериал Квест Вижна 1 сезон онлайн</title><div data-id-season="777" data-id-serial="40123"></div>
            <script>var data4play = {'secureMark': 'abc123', 'time': 1700000000}</script>
            <ul><li data-click="translate" data-translate="0">Стандартный</li><li data-click="translate" data-translate="LostFilm">LostFilm</li></ul>"""
        val p = Seasonvar.parseSeasonPage(html)!!
        assertEquals("777", p.seasonId)
        assertEquals("abc123", p.secure)
        assertEquals("1700000000", p.time)
        assertEquals(listOf("Стандартный", "LostFilm"), p.voices.map { it.second })
        assertEquals("https://seasonvar.ru/playls2/abc123/transLostFilm/777/plist.txt?time=1700000000", Seasonvar.playlistUrl(p, "LostFilm"))
        val b64 = Base64.getEncoder().encodeToString("https://s.cdn/1.mp4".toByteArray())
        val spoiled = "#2" + b64.substring(0, 6) + "//b2xvbG8=" + b64.substring(6)
        val eps = Seasonvar.parsePlaylist("""[{"title":"1 серия SD<br>","file":"$spoiled"},{"title":"2 серия","file":"https://s.cdn/2.mp4"}]""", 1)
        assertEquals(listOf(1, 2), eps.map { it.episode })
        assertEquals("https://s.cdn/1.mp4", eps[0].files[0].second)
    }

    @Test fun rutube() {
        val videos = Rutube.parseSearch("""{"results":[
            {"id":"aa","title":"Дюна — трейлер","duration":150},
            {"id":"bb","title":"Дюна (2021) фильм","duration":9300,"thumbnail_url":"https://pic"},
            {"id":"cc","title":"Квест Вижна 1 сезон 2 серия","duration":2700},
            {"id":"dd","title":"Квест Вижна серия 1","duration":2700}]}""")
        assertEquals("bb", Rutube.film(videos, listOf("Дюна"), 2021)!!.id)
        assertNull(Rutube.film(videos, listOf("Дюна"), 1984))
        val eps = Rutube.episodes(videos, listOf("Квест Вижна"))
        assertEquals(listOf(1, 2), eps.map { it.episode })
        assertEquals("https://h/x.m3u8", Rutube.parseOptions("""{"video_balancer":{"m3u8":"https://h/x.m3u8"}}"""))
    }
}
