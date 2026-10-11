package app.couchy.core

import app.couchy.core.sources.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {
    @Test
    fun buildsSearchUrls() {
        assertEquals(
            "https://rest.opensubtitles.org/search/episode-2/imdbid-0903747/season-1/sublanguageid-rus,eng",
            Subtitles.searchUrl("tt0903747", null, 1, 2),
        )
        assertEquals(
            "https://rest.opensubtitles.org/search/query-breaking%20bad/sublanguageid-rus,eng",
            Subtitles.searchUrl(null, "Breaking Bad", null, null),
        )
    }

    @Test
    fun parsesRussianFirst() {
        val json = """[
            {"SubFileName":"a.eng.srt","SubDownloadLink":"https://x/1.gz","SubLanguageID":"eng","SubFormat":"srt","SubDownloadsCnt":"900"},
            {"SubFileName":"a.rus.srt","SubDownloadLink":"https://x/2.gz","SubLanguageID":"rus","SubFormat":"srt","SubDownloadsCnt":"5","SubEncoding":"CP1251"},
            {"SubFileName":"a.sub","SubDownloadLink":"https://x/3.gz","SubLanguageID":"rus","SubFormat":"sub"}
        ]"""
        val hits = Subtitles.parse(json)
        assertEquals(listOf("rus", "eng"), hits.map { it.lang })
        assertEquals("CP1251", hits.first().encoding)
    }

    @Test
    fun readsNamesAndEpisodes() {
        assertEquals("Breaking Bad", Subtitles.queryOf("Breaking.Bad.S01E02.1080p.WEB.mkv"))
        assertEquals("Дюна", Subtitles.queryOf("Дюна (2021) 1080p"))
        assertEquals("Во все тяжкие", Subtitles.queryOf("Во все тяжкие · S1E2"))
        assertEquals(1 to 2, Subtitles.episodeOf("Во все тяжкие · S1E2"))
        assertEquals(3 to 10, Subtitles.episodeOf("show.3x10.avi"))
    }

    @Test
    fun decodesCyrillic() {
        val text = "Привет"
        assertEquals(text, Subtitles.decode(text.toByteArray(Charsets.UTF_8), "CP1251"))
        assertEquals(text, Subtitles.decode(text.toByteArray(charset("windows-1251")), "CP1251"))
        assertEquals(text, Subtitles.decode(text.toByteArray(charset("windows-1251")), null))
    }
}
