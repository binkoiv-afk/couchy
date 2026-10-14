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
}
