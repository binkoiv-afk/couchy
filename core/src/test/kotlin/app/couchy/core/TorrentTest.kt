package app.couchy.core

import app.couchy.core.torrent.Episode
import app.couchy.core.torrent.Magnet
import app.couchy.core.torrent.Sizes
import app.couchy.core.torrent.TorrFile
import app.couchy.core.torrent.TorrFiles
import app.couchy.core.torrent.TorrentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentTest {
    @Test fun magnetHash() {
        assertEquals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a", Magnet.hash("magnet:?xt=urn:btih:C12FE1C06BBA254A9DC9F519B335AA7C1367A88A&dn=x"))
        // Base32 form of the same hash.
        assertEquals("c12fe1c06bba254a9dc9f519b335aa7c1367a88a", Magnet.hash("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK"))
        assertNull(Magnet.hash("magnet:?dn=nothing"))
        assertEquals("Мой фильм", Links.titleFor(Magnet.fromHash("ab", "Мой фильм")))
    }

    @Test fun refs() {
        val ref = TorrentRef.of("magnet:?xt=urn:btih:abc", 3)
        assertEquals("magnet:?xt=urn:btih:abc" to 3, TorrentRef.parse(ref))
        assertEquals(ref, TorrentRef.of(ref, 3))
        assertEquals("http://t/x.torrent" to null, TorrentRef.parse("http://t/x.torrent"))
        assertEquals(LinkKind.TORRENT, Links.classify(TorrentRef.of("http://t/x.torrent", 2)))
    }

    @Test fun playableOrder() {
        val files = listOf(
            TorrFile(1, "Show/Show.S01E10.mkv", 900_000_000),
            TorrFile(2, "Show/Show.S01E2.mkv", 900_000_000),
            TorrFile(3, "Show/sample.mkv", 20_000_000),
            TorrFile(4, "Show/Show.S01E1.mkv", 900_000_000),
            TorrFile(5, "Show/info.nfo", 1_000),
            TorrFile(6, "Show/Subs/rus.srt", 50_000),
        )
        assertEquals(listOf(4, 2, 1), TorrFiles.playable(files).map { it.id })
        assertEquals(1, TorrFiles.next(files, 2)?.id)
        assertNull(TorrFiles.next(files, 1))
    }

    @Test fun episodes() {
        assertEquals(Episode(2, 5), TorrFiles.episodeOf("a/Show.S02E05.1080p.mkv"))
        assertEquals(Episode(1, 12), TorrFiles.episodeOf("Show 1x12.avi"))
        assertEquals(Episode(null, 7), TorrFiles.episodeOf("Сериал - Серия 7.mkv"))
        assertEquals(Episode(null, 3), TorrFiles.episodeOf("03. Название.mkv"))
        assertNull(TorrFiles.episodeOf("Movie.2019.1080p.mkv"))
        assertEquals("S2 · Серия 5", TorrFiles.label(TorrFile(1, "Show.S02E05.mkv", 1), single = false))
        assertEquals("Movie 2019 1080p", TorrFiles.label(TorrFile(1, "Movie.2019.1080p.mkv", 1), single = true))
        assertEquals("Орбита · S1E3", TorrFiles.title("Орбита", TorrFile(1, "Orbit.S01E03.mkv", 1), single = false))
    }

    @Test fun sizes() {
        assertEquals("1,4 ГБ", Sizes.bytes(1_500_000_000))
        assertEquals("700 МБ", Sizes.bytes(734_003_200))
        assertEquals("3,4 МБ/с", Sizes.speed(3.4 * 1024 * 1024))
        assertEquals("512 КБ/с", Sizes.speed(512.0 * 1024))
    }
}
