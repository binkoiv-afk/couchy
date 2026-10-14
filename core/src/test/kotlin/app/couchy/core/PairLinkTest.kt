package app.couchy.core

import app.couchy.core.pair.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    @Test
    fun roundTrip() {
        val l = PairLink("192.168.1.40", 18300, "482913", "Xiaomi Mi Box S")
        assertEquals(l, PairLink.parse(l.toLink()))
    }

    @Test
    fun rejectsOtherText() {
        assertNull(PairLink.parse("https://example.com"))
        assertNull(PairLink.parse("couchy://pair?h=1.2.3.4&c=123456"))
        assertNull(PairLink.parse("couchy://pair?h=1.2.3.4:18300&c=abc"))
    }

    @Test
    fun codeIsSixDigits() {
        repeat(50) { assertEquals(6, PairLink.newCode().length) }
    }

    @Test
    fun onlyHomeNetwork() {
        assertNull(PairLink.parse("couchy://pair?h=evil.example:80&c=123456"))
        assertNull(PairLink.parse("couchy://pair?h=8.8.8.8:18300&c=123456"))
        assertEquals("10.0.0.7", PairLink.parse("couchy://pair?h=10.0.0.7:18300&c=123456")?.host)
        assertEquals("172.20.1.2", PairLink.parse("couchy://pair?h=172.20.1.2:18300&c=123456")?.host)
        assertNull(PairLink.parse("couchy://pair?h=172.32.1.2:18300&c=123456"))
    }
}
