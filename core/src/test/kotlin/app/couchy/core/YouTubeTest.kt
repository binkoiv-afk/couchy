package app.couchy.core

import app.couchy.core.youtube.SponsorBlock
import app.couchy.core.youtube.YouTubeFeed
import app.couchy.core.youtube.YouTubeLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTest {
    @Test fun videoIds() {
        assertEquals("dQw4w9WgXcQ", YouTubeLinks.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", YouTubeLinks.videoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeLinks.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", YouTubeLinks.videoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeLinks.videoId("https://www.youtube.com/live/dQw4w9WgXcQ"))
        assertNull(YouTubeLinks.videoId("https://www.youtube.com/@channel"))
        assertNull(YouTubeLinks.videoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", YouTubeLinks.channelId("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"))
    }

    @Test fun feed() {
        val xml = """
            <feed><entry>
              <id>yt:video:abcdefghijk</id><yt:videoId>abcdefghijk</yt:videoId>
              <title>Тест &amp; видео</title>
              <link rel="alternate" href="https://www.youtube.com/watch?v=abcdefghijk"/>
              <author><name>Канал</name><uri>https://www.youtube.com/channel/UC1</uri></author>
              <published>2026-10-09T15:00:12+00:00</published>
              <media:group><media:community><media:statistics views="1234"/></media:community></media:group>
            </entry><entry>
              <yt:videoId>shortshort1</yt:videoId><title>Short</title>
              <link rel="alternate" href="https://www.youtube.com/shorts/shortshort1"/>
            </entry></feed>
        """.trimIndent()
        val v = YouTubeFeed.parse(xml).single()
        assertEquals("abcdefghijk", v.id)
        assertEquals("Тест & видео", v.title)
        assertEquals("Канал", v.channel)
        assertEquals(1234L, v.views)
        assertEquals(1791558012000L, v.publishedMs)
    }

    @Test fun sponsorBlock() {
        val json = """[{"segment":[10.5,40.0],"category":"sponsor","actionType":"skip"},{"segment":[1,1.2],"category":"intro","actionType":"skip"},{"segment":[50,60],"category":"selfpromo","actionType":"mute"}]"""
        val s = SponsorBlock.parse(json)
        assertEquals(1, s.size)
        assertEquals(10_500L, s[0].startMs)
        assertEquals(40_000L, s[0].endMs)
    }
}
