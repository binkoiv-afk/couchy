package app.couchy.core

import app.couchy.core.music.LrcLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test fun clean() {
        assertEquals("Blinding Lights", LrcLib.clean("The Weeknd - Blinding Lights (Official Video)", "The Weeknd"))
        assertEquals("Кукла колдуна", LrcLib.clean("Король и Шут — Кукла колдуна [Official Audio]", "Король и Шут"))
        assertEquals("Song", LrcLib.clean("Song feat. Someone", "Artist"))
        assertEquals("The Weeknd", LrcLib.artistFrom("The Weeknd - Blinding Lights"))
    }

    @Test fun lrc() {
        val lines = LrcLib.parseLrc("[ar: x]\n[00:12.50]First\n[00:15.00][01:15.00]Again\n[00:20]Last")
        assertEquals(listOf(12_500L, 15_000L, 20_000L, 75_000L), lines.map { it.first })
        assertEquals(1, LrcLib.lineAt(lines, 16_000))
        assertEquals(-1, LrcLib.lineAt(lines, 1_000))
    }

    @Test fun parse() {
        val l = LrcLib.parse("""{"syncedLyrics":"[00:01.00]Hi","plainLyrics":"Hi"}""")!!
        assertTrue(l.synced)
        val p = LrcLib.parse("""{"syncedLyrics":null,"plainLyrics":"One\nTwo"}""")!!
        assertEquals(2, p.lines.size)
        val s = LrcLib.parseSearch("""[{"duration":100,"syncedLyrics":null,"plainLyrics":"a"},{"duration":200,"syncedLyrics":"[00:01.00]b","plainLyrics":"b"}]""", 100)!!
        assertTrue(s.synced)
    }
}
