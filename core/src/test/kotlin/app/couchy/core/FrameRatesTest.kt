package app.couchy.core

import app.couchy.core.player.FrameRates
import app.couchy.core.player.ScreenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    private val m60 = ScreenMode(1, 3840, 2160, 60f)
    private val m50 = ScreenMode(2, 3840, 2160, 50f)
    private val m24 = ScreenMode(3, 3840, 2160, 24f)
    private val m23 = ScreenMode(4, 3840, 2160, 23.976f)
    private val m5994 = ScreenMode(5, 3840, 2160, 59.94f)
    private val hd24 = ScreenMode(6, 1920, 1080, 24f)
    private val all = listOf(m60, m50, m24, m23, m5994, hd24)

    @Test
    fun filmsGetTheirOwnRate() {
        assertEquals(m23, FrameRates.pick(23.976f, all, m60))
        assertEquals(m24, FrameRates.pick(24f, all, m60))
        assertEquals(m50, FrameRates.pick(25f, all, m60))
        assertEquals(m5994, FrameRates.pick(29.97f, all, m60))
    }

    @Test
    fun staysWhenRightOrNothingFits() {
        assertNull(FrameRates.pick(60f, all, m60))
        assertNull(FrameRates.pick(30f, all, m60))
        assertNull(FrameRates.pick(24f, listOf(m60, hd24), m60))
        assertNull(FrameRates.pick(-1f, all, m60))
    }
}
