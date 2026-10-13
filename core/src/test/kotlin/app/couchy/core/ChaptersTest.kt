package app.couchy.core

import app.couchy.core.youtube.Chapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    @Test
    fun readsTimestampsFromDescription() {
        val d = """
            Альбом целиком
            0:00 Intro
            3:15 - Second song
            1. [7:02] Третья
            1:02:03 Финал
            Подписывайтесь!
        """.trimIndent()
        val c = Chapters.parse(d, 70 * 60_000L)
        assertEquals(4, c.size)
        assertEquals("Intro", c[0].title)
        assertEquals(195_000L, c[1].startMs)
        assertEquals("Second song", c[1].title)
        assertEquals("Третья", c[2].title)
        assertEquals(3_723_000L, c[3].startMs)
        assertEquals(70 * 60_000L, c[3].endMs)
        assertEquals(c[1].startMs, c[0].endMs)
    }

    @Test
    fun ignoresLooseTimestamps() {
        assertTrue(Chapters.parse("Смотрите с 2:30\nи с 5:00\nи 7:00", 600_000).isEmpty())
        assertTrue(Chapters.parse("0:00 a\n5:00 b\n3:00 c", 600_000).isEmpty())
    }

    @Test
    fun fileNamesSortAndAreSafe() {
        assertEquals("03. AC DC Back.m4a", Chapters.fileName(3, 12, "AC/DC: Back", "m4a"))
        assertEquals("007. x.m4a", Chapters.fileName(7, 120, "x", "m4a"))
    }
}
