package app.couchy.core

import app.couchy.core.sync.LibrarySync
import app.couchy.core.sync.SyncItem
import app.couchy.core.sync.SyncPlaylist
import app.couchy.core.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private fun item(key: String, watched: Long = 0, pos: Long = 0, fav: Boolean = false) =
        SyncItem(key, key.uppercase(), "HTTP", 100, watched, pos, 10_000, fav)

    private fun state(vararg items: SyncItem, lists: List<SyncPlaylist> = emptyList(), hosts: Set<String> = emptySet()) =
        SyncState(items.associateBy { it.key }, lists.associateBy { it.key }, hosts)

    @Test fun newestPositionWins() {
        val base = state(item("a", 10, 1000))
        val merged = LibrarySync.merge(base, state(item("a", 20, 2000)), state(item("a", 30, 3000)))
        assertEquals(3000, merged.items["a"]!!.positionMs)
    }

    @Test fun additionsFromBothSides() {
        val merged = LibrarySync.merge(SyncState(), state(item("a")), state(item("b")))
        assertEquals(setOf("a", "b"), merged.items.keys)
    }

    @Test fun deletionsTravel() {
        val base = state(item("a", 10), item("b", 10))
        // "a" deleted here, "b" deleted on the other device.
        val merged = LibrarySync.merge(base, state(item("b", 10)), state(item("a", 10)))
        assertTrue(merged.items.isEmpty())
        // Watched again on the other device after the deletion here: it comes back.
        val again = LibrarySync.merge(base, state(item("b", 10)), state(item("a", 50), item("b", 10)))
        assertEquals(setOf("a", "b"), again.items.keys)
    }

    @Test fun favoriteChangedOnOneSide() {
        val base = state(item("a", 10, fav = false))
        val merged = LibrarySync.merge(base, state(item("a", 30, fav = false)), state(item("a", 20, fav = true)))
        assertTrue(merged.items["a"]!!.favorite)
        val unfav = LibrarySync.merge(state(item("a", 10, fav = true)), state(item("a", 10, fav = false)), state(item("a", 40, fav = true)))
        assertFalse(unfav.items["a"]!!.favorite)
    }

    @Test fun playlistsAndHosts() {
        val p1 = SyncPlaylist("Мой", "M3U", "http://x/1.m3u")
        val p2 = SyncPlaylist("Xtream", "XTREAM", "http://srv", "u", "p")
        val base = state(lists = listOf(p1), hosts = setOf("rezka.ag"))
        val merged = LibrarySync.merge(base, state(lists = listOf(p1, p2), hosts = setOf("rezka.ag", "kinozal.tv")), state(hosts = setOf("rezka.ag")))
        assertEquals(setOf(p2.key), merged.playlists.keys)
        assertEquals(setOf("rezka.ag", "kinozal.tv"), merged.bypassHosts)
    }

    @Test fun jsonRoundTrip() {
        val s = state(item("magnet:?xt=urn:btih:abc#couchy-file=2", 5, 7, true).copy(audioLanguage = "ru", subtitleLanguage = ""),
            lists = listOf(SyncPlaylist("Xtream", "XTREAM", "http://srv", "u", "p")), hosts = setOf("rezka.ag"))
        assertEquals(s, SyncState.fromJson(s.toJson()))
        val (write, delete) = LibrarySync.changes(state(item("x")), s)
        assertEquals(1, write.size)
        assertEquals(setOf("x"), delete)
    }

    @Test fun kids() {
        assertTrue(Kids.isKidsGroup("Детские"))
        assertTrue(Kids.isKidsGroup("Kids"))
        assertTrue(Kids.isKidsGroup("Мультфильмы"))
        assertFalse(Kids.isKidsGroup("Новости"))
        assertFalse(Kids.isKidsGroup(null))
        assertTrue(Kids.validPin("0420"))
        assertFalse(Kids.validPin("42"))
    }
}
