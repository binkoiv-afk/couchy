package app.couchy.core

import app.couchy.core.bypass.Base64
import app.couchy.core.bypass.BypassRules
import app.couchy.core.bypass.TunnelConfig
import app.couchy.core.bypass.Warp
import app.couchy.core.bypass.WarpAccount
import app.couchy.core.bypass.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun rfc7748Vectors() {
        // RFC 7748 section 5.2, and the Alice key pair from section 6.1.
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hex(X25519.multiply(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))),
        )
        val alice = Base64.encode(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(Base64.decode(X25519.publicKey(alice))))
    }

    @Test fun base64RoundTrip() {
        for (n in 0..7) {
            val b = ByteArray(n) { (it * 37 + 5).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), Base64.encode(b))
            assertEquals(hex(b), hex(Base64.decode(Base64.encode(b))))
        }
    }

    @Test fun warpRegistration() {
        val json = """{"id":"abc","token":"tok","config":{"client_id":"Jh0Q","peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"v4":"162.159.192.6:0","v6":"[2606:4700:d0::a29f:c006]:0","host":"engage.cloudflareclient.com:2408","ports":[2408,500,1701,4500]}}],"interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:88d0:c020:9a7f:2edc:8a99"}}}}"""
        val key = X25519.privateKey()
        val a = Warp.parseReg(json, key)
        assertEquals("162.159.192.6", a.endpointHost)
        assertEquals(listOf(2408, 500, 1701, 4500), a.ports)
        assertEquals(a, WarpAccount.fromJson(a.toJson()))
        val conf = Warp.config(a)
        assertTrue("Endpoint = 162.159.192.6:2408" in conf)
        assertTrue("Address = 172.16.0.2/32, 2606:4700:110:88d0:c020:9a7f:2edc:8a99/128" in conf)
        assertTrue("Jc = 4" in conf)
        assertTrue(Warp.regBody(X25519.publicKey(key)).contains("\"tos\":\""))
    }

    @Test fun ownConfig() {
        val text = """
            [Interface]
            PrivateKey = uCTIK+56CPyCvwJxmU5dBfuyJvPuSXAq1FzHdnIxe1Q=
            Address = 10.8.0.2/32
            DNS = 1.1.1.1
            Table = off
            PostUp = iptables -A FORWARD
            Jc = 5

            [Peer]
            PublicKey = QP+A67Z2UBrMgvNIdHv8gPel5URWNLS4B3ZQ2hQIZlg=
            Endpoint = vpn.example.com:51820
            AllowedIPs = 0.0.0.0/0

            [Socks5]
            BindAddress = 0.0.0.0:1080
        """.trimIndent()
        assertNull(TunnelConfig.problem(text))
        assertNotNull(TunnelConfig.problem("[Interface]\nPrivateKey = x"))
        val out = TunnelConfig.withProxy(text, 18090)
        assertFalse("Table" in out)
        assertFalse("PostUp" in out)
        assertFalse("0.0.0.0:1080" in out)
        assertTrue("Jc = 5" in out)
        assertTrue(out.trimEnd().endsWith("[http]\nBindAddress = 127.0.0.1:18090"))
    }

    @Test fun rules() {
        assertTrue(BypassRules.matches("hdrezka.ag", BypassRules.preset))
        assertTrue(BypassRules.matches("cdn1.rezka.ag", BypassRules.preset))
        assertFalse(BypassRules.matches("notrezka.ag", BypassRules.preset))
        assertEquals("example.com", BypassRules.domainOf("cdn3.example.com"))
        assertEquals("bbc.co.uk", BypassRules.domainOf("www.bbc.co.uk"))
        assertTrue(BypassRules.isLocal("127.0.0.1"))
        assertTrue(BypassRules.isLocal("192.168.1.20"))
        assertFalse(BypassRules.isLocal("172.64.1.1"))
    }

    @Test fun blockPagesAndTrace() {
        assertTrue(BypassRules.isBlockPage("http://warning.rt.ru/?id=17"))
        assertFalse(BypassRules.isBlockPage("https://rezka.ag/films/"))
        assertEquals("выход через NL, WARP", BypassRules.traceSummary("fl=1\nip=104.28.1.1\nloc=NL\nwarp=on\n"))
        assertEquals("выход через RU", BypassRules.traceSummary("loc=RU\nwarp=off"))
    }
}
