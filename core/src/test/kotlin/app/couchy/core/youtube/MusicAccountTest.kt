package app.couchy.core.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAccountTest {
    private val home = JSONObject(
        """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Быстрый выбор"}]}}},
            "contents":[
              {"musicResponsiveListItemRenderer":{
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://a/60"},{"url":"https://a/120"}]}}},
                "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"abc123"}}}}}},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Кукушка"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Песня"},{"text":" • "},{"text":"Кино"},{"text":" • "},{"text":"Последний герой"}]}}}
                ],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"6:35"}]}}}]
              }}
            ]}},
          {"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Миксы для вас"}]}}},
            "contents":[
              {"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Микс 1"}]},
                "subtitle":{"runs":[{"text":"Кино, Сплин"}]},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"//b/226"}]}}},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDTMAK5uy_x"}}
              }},
              {"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Группа крови"}]},
                "subtitle":{"runs":[{"text":"Альбом"},{"text":" • "},{"text":"Кино"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_xyz"}}
              }}
            ]}}
        ],"continuations":[{"nextContinuationData":{"continuation":"TOKEN1"}}]}}}}]}}}
        """,
    )

    @Test
    fun readsShelves() {
        val shelves = MusicAccount.shelves(home)
        assertEquals(listOf("Быстрый выбор", "Миксы для вас"), shelves.map { it.title })
        val song = shelves[0].songs.single()
        assertEquals("abc123", song.id)
        assertEquals("Кукушка", song.title)
        assertEquals("Кино", song.channel)
        assertEquals(395L, song.durationSec)
        assertEquals("https://a/120", song.thumb)
        val tiles = shelves[1].tiles
        assertEquals("https://music.youtube.com/playlist?list=RDTMAK5uy_x", tiles[0].url)
        assertEquals("https://b/226", tiles[0].thumb)
        assertEquals("https://music.youtube.com/browse/MPREb_xyz", tiles[1].url)
        assertEquals("TOKEN1", MusicAccount.continuation(home))
    }

    @Test
    fun readsQueue() {
        val next = JSONObject(
            """{"contents":{"playlistPanelRenderer":{"contents":[
              {"playlistPanelVideoRenderer":{"videoId":"v1","title":{"runs":[{"text":"Звезда"}]},
                "shortBylineText":{"runs":[{"text":"Кино"}]},"lengthText":{"runs":[{"text":"3:45"}]},
                "thumbnail":{"thumbnails":[{"url":"https://c/1"}]}}}]}}}""",
        )
        val songs = MusicAccount.items(next).first
        assertEquals("v1", songs.single().id)
        assertEquals(225L, songs.single().durationSec)
    }

    @Test
    fun authorization() {
        val cookies = "HSID=x; SAPISID=secret123; SID=y"
        assertTrue(MusicAccount.signedIn(cookies))
        val auth = MusicAccount.authorization(cookies, 1700000000)!!
        assertTrue(auth.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, auth.substringAfter('_').length)
        assertNull(MusicAccount.authorization("SID=y", 1))
        assertEquals("VLPL1", MusicAccount.browseIdOf("https://music.youtube.com/playlist?list=PL1"))
        assertEquals("MPREb", MusicAccount.browseIdOf("https://music.youtube.com/browse/MPREb"))
    }
}
