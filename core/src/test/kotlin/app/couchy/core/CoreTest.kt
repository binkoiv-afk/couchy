package app.couchy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test fun findsIptvPlaylistsWithoutM3uName() {
        assertEquals(LinkKind.PLAYLIST, Links.classify("http://panel.example:8080/get.php?username=u&password=p&type=m3u_plus&output=ts"))
        assertEquals(LinkKind.PLAYLIST, Links.classify("https://x.org/playlist?type=m3u"))
        val iptv = "#EXTM3U url-tvg=\"http://e/epg.xml\"\n#EXTINF:-1 tvg-id=\"1\" group-title=\"Новости\",Первый\nhttp://s/1.m3u8\n"
        assertEquals(LinkKind.PLAYLIST, Links.sniff(iptv))
        assertEquals(LinkKind.PLAYLIST, Links.sniff("\uFEFF#EXTM3U\n#EXTINF:-1,A\nhttp://a\n#EXTINF:-1,B\nhttp://b\n"))
        assertEquals(LinkKind.HLS, Links.sniff("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertEquals(LinkKind.HLS, Links.sniff("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n"))
        assertEquals(null, Links.sniff("<html>"))
        assertEquals(
            Triple("http://panel.example:8080", "u", "p 1"),
            Links.xtream("http://panel.example:8080/get.php?username=u&password=p%201&type=m3u_plus"),
        )
        assertEquals(null, Links.xtream("http://iptv.example/list.m3u"))
    }

    @Test fun classifiesCommonLinks() {
        assertEquals(LinkKind.HLS, Links.classify("https://cdn.example.com/hls/master.m3u8?token=1"))
        assertEquals(LinkKind.DASH, Links.classify("https://x.org/a/manifest.mpd"))
        assertEquals(LinkKind.PLAYLIST, Links.classify("http://iptv.example/list.m3u"))
        assertEquals(LinkKind.TORRENT, Links.classify("magnet:?xt=urn:btih:abc&dn=Film"))
        assertEquals(LinkKind.TORRENT, Links.classify("https://t.org/file.torrent"))
        assertEquals(LinkKind.YOUTUBE, Links.classify("https://www.youtube.com/watch?v=abc"))
        assertEquals(LinkKind.YOUTUBE, Links.classify("https://youtu.be/abc"))
        assertEquals(LinkKind.VIDEO, Links.classify("https://x.org/Movie.MKV"))
        assertEquals(LinkKind.UNKNOWN, Links.classify("https://x.org/play"))
    }

    @Test fun titlesFromLinks() {
        assertEquals("Severny veter 2025 1080p", Links.titleFor("https://x.org/v/Severny.veter.2025.1080p.mkv"))
        assertEquals("Мой фильм", Links.titleFor("https://x.org/%D0%9C%D0%BE%D0%B9_%D1%84%D0%B8%D0%BB%D1%8C%D0%BC.mp4"))
        assertEquals("Film Name", Links.titleFor("magnet:?xt=urn:btih:abc&dn=Film.Name"))
        assertEquals("example.com", Links.titleFor("https://www.example.com/"))
    }

    @Test fun recognisesLinks() {
        assertTrue(Links.looksLikeLink(" https://a.b/c "))
        assertTrue(Links.looksLikeLink("magnet:?xt=1"))
        assertFalse(Links.looksLikeLink("северный ветер"))
    }
}

class ProgressTest {
    private val hour = 3_600_000L

    @Test fun resumeRules() {
        assertEquals(0, Progress.resumeFrom(10_000, hour))
        assertEquals(1_800_000, Progress.resumeFrom(1_800_000, hour))
        assertEquals(0, Progress.resumeFrom(hour - 60_000, hour))
        assertEquals(60_000, Progress.resumeFrom(60_000, 0))
    }

    @Test fun labels() {
        assertEquals("51:22", Time.clock(51 * 60_000L + 22_000))
        assertEquals("2:15:04", Time.clock(2 * hour + 15 * 60_000 + 4_000))
        assertEquals("ещё 1 ч 22 мин", Progress.remainingLabel(38 * 60_000L, 2 * hour))
        assertEquals("ещё 9 мин", Progress.remainingLabel(hour - 9 * 60_000, hour))
    }
}

class SortTest {
    private data class E(
        override val title: String,
        override val addedAt: Long,
        override val lastWatchedAt: Long,
        override val positionMs: Long,
        override val durationMs: Long,
    ) : Sortable

    private val items = listOf(
        E("Бета", 1, 30, 0, 0),
        E("альфа", 2, 10, 50 * 60_000L, 100 * 60_000L),
        E("Гамма", 3, 20, 99 * 60_000L, 100 * 60_000L),
        E("Дельта", 4, 40, 90 * 60_000L, 120 * 60_000L),
    )

    @Test fun orders() {
        assertEquals(listOf("Дельта", "Бета", "Гамма", "альфа"), LibrarySort.sort(items, SortOrder.RECENTLY_WATCHED).map { it.title })
        assertEquals(listOf("альфа", "Бета", "Гамма", "Дельта"), LibrarySort.sort(items, SortOrder.TITLE).map { it.title })
        assertEquals(listOf("Дельта", "альфа", "Бета", "Гамма"), LibrarySort.sort(items, SortOrder.PROGRESS).map { it.title })
    }

    @Test fun continueRow() {
        assertEquals(listOf("Дельта", "альфа"), LibrarySort.continueWatching(items).map { it.title })
    }
}
