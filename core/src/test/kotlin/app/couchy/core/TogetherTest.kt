package app.couchy.core

import app.couchy.core.together.LocalPlay
import app.couchy.core.together.Together
import app.couchy.core.together.TogetherMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherTest {
    @Test
    fun codes() {
        val c = Together.newCode()
        assertEquals(8, c.length)
        assertEquals(c, Together.normalize(Together.pretty(c).lowercase()))
        assertNull(Together.normalize("ABCD-EFG"))
        assertNull(Together.normalize("ABCD-EFG0"))
    }

    @Test
    fun messagesRoundTrip() {
        listOf(
            TogetherMessage.State("a", "https://x#couchy-rezka=1:1:2", "Тьма · S1E2", 61_000, true),
            TogetherMessage.Chat("a", "Игорь", "Погнали"),
            TogetherMessage.Hello("b", "Mi Box"),
            TogetherMessage.Bye("b", "Mi Box"),
        ).forEach { assertEquals(it, TogetherMessage.decode(it.encode())) }
        assertNull(TogetherMessage.decode("hello"))
    }

    @Test
    fun steadyPlaybackIsNotAChange() {
        val a = LocalPlay("k", "t", 10_000, true, 1_000)
        assertFalse(Together.changed(a, a.copy(positionMs = 10_500, at = 1_500)))
        assertTrue(Together.changed(a, a.copy(positionMs = 40_000, at = 1_500)))
        assertTrue(Together.changed(a, a.copy(playing = false, at = 1_500)))
        assertFalse(Together.changed(a.copy(playing = false), a.copy(playing = false, at = 9_000)))
    }

    @Test
    fun follow() {
        val there = TogetherMessage.State("b", "k", "t", 60_000, false)
        assertEquals(listOf(Together.Follow.Open("k", "t", 60_000)), Together.follow(null, there))
        assertEquals(
            listOf(Together.Follow.Seek(60_000), Together.Follow.Play(false)),
            Together.follow(LocalPlay("k", "t", 10_000, true, 0), there),
        )
        assertEquals(emptyList<Together.Follow>(), Together.follow(LocalPlay("k", "t", 59_000, false, 0), there))
    }
}
