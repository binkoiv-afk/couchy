package app.couchy.core

import app.couchy.core.sources.Rezka
import org.junit.Assert.assertEquals
import org.junit.Test

class RezkaQualityTest {
    @Test
    fun paidQualitiesAreLeftOut() {
        val text = "[720p]https://a/720.m3u8 or https://a/720.mp4," +
            "[1080p]https://a/1080.m3u8 or https://a/1080.mp4," +
            "[<span class=\"pjs-registered-quality\">1080p Ultra<img src=\"x\"></span>]https://a/u.m3u8 or https://a/u.mp4"
        val q = Rezka.parseQualities(text)
        assertEquals(listOf("720p", "1080p"), q.map { it.quality })
        assertEquals("https://a/1080.mp4", q[1].url)
    }
}
