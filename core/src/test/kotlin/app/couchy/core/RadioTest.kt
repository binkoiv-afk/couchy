package app.couchy.core

import app.couchy.core.radio.RadioBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioTest {
    @Test fun parsesAndDropsDuplicates() {
        val json = """[
          {"stationuuid":"a","name":"Европа Плюс","url":"http://x/a","url_resolved":"https://x/a.mp3","favicon":"https://x/a.png","tags":"pop,hits","country":"The Russian Federation","codec":"MP3","bitrate":128},
          {"stationuuid":"b","name":"Европа  плюс!","url_resolved":"https://x/b.mp3","tags":"","country":"","codec":"AAC","bitrate":0},
          {"stationuuid":"c","name":"","url_resolved":"https://x/c.mp3"},
          {"stationuuid":"d","name":"Jazz","url_resolved":"ftp://x/d","favicon":""}
        ]"""
        val list = RadioBrowser.parse(json)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("https://x/a.mp3", s.url)
        assertEquals("pop, hits · Россия · 128 кбит/с", s.about)
        assertEquals(s, RadioBrowser.parse(RadioBrowser.toJson(list))[0])
    }

    @Test fun songLine() {
        assertEquals("Queen — Bohemian Rhapsody", RadioBrowser.songLine("Queen - Bohemian Rhapsody"))
        assertEquals("Утреннее шоу", RadioBrowser.songLine(" Утреннее шоу "))
        assertNull(RadioBrowser.songLine(" "))
    }
}
