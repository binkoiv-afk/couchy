package app.couchy.core

import app.couchy.core.iptv.Catchup
import app.couchy.core.iptv.CatchupUrl
import app.couchy.core.iptv.Epg
import app.couchy.core.iptv.M3u
import app.couchy.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    private val sample = """
        #EXTM3U url-tvg="http://epg.example/epg.xml.gz, http://epg2.example/e.xml" catchup="shift" catchup-days="5"
        #EXTINF:-1 tvg-id="kino.hd" tvg-name="Кино HD" tvg-logo="http://l/k.png" group-title="Кино",Кино HD
        http://iptv.example/kino/index.m3u8
        #EXTINF:-1 tvg-id="news24" catchup="flussonic" catchup-days="3",Новости, 24
        #EXTGRP:Новости
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        http://iptv.example/news/mpegts
        #EXTINF:0,Без группы
        udp://@239.0.0.1:1234
    """.trimIndent()

    @Test fun parsesChannels() {
        val list = M3u.parse(sample)
        assertEquals(listOf("http://epg.example/epg.xml.gz", "http://epg2.example/e.xml"), list.epgUrls)
        assertEquals(3, list.channels.size)
        val kino = list.channels[0]
        assertEquals("Кино HD", kino.name)
        assertEquals("Кино", kino.group)
        assertEquals("kino.hd", kino.tvgId)
        assertEquals("http://l/k.png", kino.logo)
        assertEquals(Catchup(Catchup.Type.SHIFT, null, 5), kino.catchup)
        val news = list.channels[1]
        assertEquals("Новости, 24", news.name)
        assertEquals("Новости", news.group)
        assertEquals("Mozilla/5.0", news.userAgent)
        assertEquals(Catchup.Type.FLUSSONIC, news.catchup?.type)
        assertEquals(3, news.catchup?.days)
        assertNull(list.channels[2].group)
        assertTrue(M3u.looksLikeM3u("﻿#EXTM3U\n"))
    }
}

class CatchupTest {
    private val now = 1_760_000_000_000L
    private val start = now - 2 * 3_600_000L
    private val end = start + 3_600_000L

    @Test fun shift() {
        assertEquals(
            "http://h/c.m3u8?a=1&utc=${start / 1000}&lutc=${now / 1000}",
            CatchupUrl.build("http://h/c.m3u8?a=1", Catchup(Catchup.Type.SHIFT, null, 3), start, end, now),
        )
    }

    @Test fun flussonic() {
        assertEquals(
            "http://h/ch/index-${start / 1000}-3600.m3u8",
            CatchupUrl.build("http://h/ch/index.m3u8", Catchup(Catchup.Type.FLUSSONIC, null, 3), start, end, now),
        )
        assertEquals(
            "http://h/ch/timeshift_abs-${start / 1000}.ts",
            CatchupUrl.build("http://h/ch/mpegts", Catchup(Catchup.Type.FLUSSONIC, null, 3), start, end, now),
        )
    }

    @Test fun templateSource() {
        val url = CatchupUrl.build(
            "http://h/live",
            Catchup(Catchup.Type.DEFAULT, "http://h/rec?start=\${start}&dur={duration}&d={utc:Y-m-d}", 3),
            start, end, now,
        )
        assertEquals("http://h/rec?start=${start / 1000}&dur=3600&d=2025-10-09", url)
    }

    @Test fun xtream() {
        val url = CatchupUrl.build("http://srv:8080/live/u/p/42.ts", Catchup(Catchup.Type.XTREAM, null, 3), start, end, now)
        assertTrue(url!!.startsWith("http://srv:8080/timeshift/u/p/60/"))
        assertTrue(url.endsWith("/42.ts"))
    }

    @Test fun outOfRange() {
        assertNull(CatchupUrl.build("http://h/c", Catchup(Catchup.Type.SHIFT, null, 1), now - 3 * 86_400_000L, now, now))
    }
}

class EpgTest {
    @Test fun times() {
        assertEquals(1_760_166_000_000L, Epg.parseTime("20251011070000 +0000"))
        assertEquals(1_760_155_200_000L, Epg.parseTime("20251011070000 +0300"))
        assertEquals(1_760_166_000_000L, Epg.parseTime("20251011070000"))
        assertNull(Epg.parseTime("bad"))
    }

    @Test fun names() {
        assertEquals(Epg.normalize("Кино HD"), Epg.normalize("кино"))
        assertEquals("первыйканал", Epg.normalize("Первый канал (+2)").replace("2", ""))
    }

    @Test fun xtreamUrls() {
        val account = XtreamAccount("srv.example:8080/", "user", "p@ss")
        assertEquals("http://srv.example:8080/player_api.php?username=user&password=p%40ss&action=get_live_streams", account.api("get_live_streams"))
        assertEquals("http://srv.example:8080/live/user/p%40ss/7.ts", account.liveUrl(7))
    }
}
