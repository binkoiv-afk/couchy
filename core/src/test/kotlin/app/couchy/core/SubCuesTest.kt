package app.couchy.core

import app.couchy.core.sources.SubCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubCuesTest {
    @Test
    fun readsSrt() {
        val cues = SubCues.parse("1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:01:00,000 --> 00:01:02,000\r\n{\\an8}Bye\r\n")
        assertEquals(2, cues.size)
        assertEquals("Hello\nthere", SubCues.at(cues, 2_000))
        assertNull(SubCues.at(cues, 4_000))
        assertEquals("Bye", SubCues.at(cues, 61_000))
    }

    @Test
    fun readsVttWithoutHours() {
        val cues = SubCues.parse("WEBVTT\n\n00:05.000 --> 00:07.250 align:start\nHi\n")
        assertEquals(5_000L, cues.single().startMs)
        assertEquals(7_250L, cues.single().endMs)
    }
}
