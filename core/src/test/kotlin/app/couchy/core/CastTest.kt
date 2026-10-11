package app.couchy.core

import app.couchy.core.cast.CastHttp
import app.couchy.core.cast.CastRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastTest {
    @Test fun roundTrip() {
        val r = CastRequest("https://rezka.ag/films/1-dune.html#couchy-rezka=56:0:0", "Дюна", 61_000, 9_000_000, mapOf("User-Agent" to "x"), "Pixel")
        assertEquals(r, CastRequest.fromJson(r.toJson()))
        assertNull(CastRequest.fromJson("{}"))
        assertNull(CastRequest.fromJson("not json"))
    }

    @Test fun localOnly() {
        assertTrue(CastRequest.isLocalOnly("content://media/external/video/1"))
        assertTrue(CastRequest.isLocalOnly("http://127.0.0.1:8090/stream/x"))
        assertFalse(CastRequest.isLocalOnly("magnet:?xt=urn:btih:abc#couchy-file=2"))
    }

    @Test fun http() {
        val body = "{\"link\":\"х\"}"
        val raw = "POST /play HTTP/1.1\r\nHost: tv\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()
        assertNull(CastHttp.parse(raw.copyOf(raw.size - 2)))
        val req = CastHttp.parse(raw)!!
        assertEquals("POST", req.method)
        assertEquals("/play", req.path)
        assertEquals(body, req.body)
        assertTrue(String(CastHttp.response(200, "{}")).startsWith("HTTP/1.1 200 OK"))
    }
}
