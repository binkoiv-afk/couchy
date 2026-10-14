package app.couchy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingTest {
    private data class Item(
        override val title: String,
        override val lastWatchedAt: Long,
        override val positionMs: Long = 600_000,
        override val durationMs: Long = 3_000_000,
        override val addedAt: Long = 0,
    ) : Sortable

    @Test
    fun seriesShowsOnceWithLatestEpisode() {
        val list = listOf(
            Item("Тьма · S1E2", 10),
            Item("Тьма · S1E5", 30),
            Item("Дюна", 20),
            Item("тьма · S1E5", 5),
        )
        assertEquals(listOf("Тьма · S1E5", "Дюна"), LibrarySort.continueWatching(list).map { it.title })
    }

    @Test
    fun filmsWithSameNameStaySeparate() {
        val list = listOf(Item("Дюна", 2, positionMs = 700_000), Item("Дюна", 1))
        assertEquals(2, LibrarySort.continueWatching(list).size)
    }

    @Test
    fun finishedEpisodeGivesWayToNext() {
        val list = listOf(
            Item("Тьма · S1E4", 10),
            Item("Тьма · S1E5", 30, positionMs = 2_990_000),
            Item("Дюна", 20, positionMs = 2_990_000),
        )
        val next = { i: Item -> if (i.title == "Тьма · S1E5") Item("Тьма · S1E6", i.lastWatchedAt, positionMs = 0) else null }
        assertEquals(listOf("Тьма · S1E6"), LibrarySort.continueWatching(list, next).map { it.title })
        assertEquals(emptyList<String>(), LibrarySort.continueWatching(list).map { it.title })
    }

    @Test
    fun seriesName() {
        assertEquals("тьма", LibrarySort.seriesOf("Тьма · S2E10"))
        assertNull(LibrarySort.seriesOf("Тьма"))
    }
}
