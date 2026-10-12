package app.couchy.core

import app.couchy.core.bypass.ProxyLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyLinkTest {
    private fun out(link: String, ip: String? = null) = JSONObject(ProxyLink.config(link, 18090, ip)).getJSONArray("outbounds").getJSONObject(0)

    @Test fun vlessReality() {
        val link = "vless://0b5f1c2a-1111-2222-3333-444455556666@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=AbCdEf&sid=6ba8&flow=xtls-rprx-vision#My%20server"
        assertNull(ProxyLink.problem(link))
        assertEquals("example.com", ProxyLink.host(link))
        val o = out(link, "1.2.3.4")
        assertEquals("vless", o.getString("protocol"))
        val server = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("1.2.3.4", server.getString("address"))
        assertEquals("xtls-rprx-vision", server.getJSONArray("users").getJSONObject(0).getString("flow"))
        val r = o.getJSONObject("streamSettings").getJSONObject("realitySettings")
        assertEquals("www.microsoft.com", r.getString("serverName"))
        assertEquals("AbCdEf", r.getString("publicKey"))
    }

    @Test fun vlessWsTls() {
        val o = out("vless://id@cdn.example.org:8443?type=ws&security=tls&path=%2Fws&host=cdn.example.org")
        val s = o.getJSONObject("streamSettings")
        assertEquals("ws", s.getString("network"))
        assertEquals("/ws", s.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.org", s.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test fun trojanAndShadowsocks() {
        assertEquals("tls", out("trojan://secret@t.example.com:443?sni=t.example.com").getJSONObject("streamSettings").getString("security"))
        val ss = out("ss://" + java.util.Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pass".toByteArray()) + "@5.6.7.8:8388#x")
        val server = ss.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("chacha20-ietf-poly1305", server.getString("method"))
        assertEquals(8388, server.getInt("port"))
    }

    @Test fun vmess() {
        val json = """{"v":"2","ps":"x","add":"v.example.com","port":"443","id":"abc","aid":"0","net":"ws","path":"/p","tls":"tls","sni":"v.example.com"}"""
        val o = out("vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals("vmess", o.getString("protocol"))
        assertEquals("/p", o.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
    }

    @Test fun problems() {
        assertNotNull(ProxyLink.problem("[Interface]"))
        assertNotNull(ProxyLink.problem("vless://id@host:443?security=reality"))
        assertTrue(ProxyLink.isLink("  VLESS://x"))
    }
}
