package app.couchy.core

import app.couchy.core.sources.Episodes
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class EpisodesTest {
    private val json = """
        {"meta":{"videos":[
          {"season":1,"episode":1,"released":"2024-01-01T00:00:00.000Z","name":"Pilot"},
          {"season":0,"episode":1,"released":"2024-01-02T00:00:00.000Z"},
          {"season":1,"episode":2,"released":"2024-01-08T00:00:00.000Z"},
          {"season":1,"episode":3,"released":"2030-01-15T00:00:00.000Z"},
          {"season":1,"episode":2,"released":"2024-01-08T00:00:00.000Z"}
        ]}}
    """.trimIndent()
    private val now = java.time.Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun parsesAndSkipsSpecials() {
        val list = Episodes.parseCinemeta(json)
        assertEquals(3, list.size)
        assertEquals("Pilot", list.first().name)
        assertEquals(2, Episodes.aired(list, now).size)
    }

    @Test
    fun firstLookIsSilent() {
        val list = Episodes.parseCinemeta(json)
        assertTrue(Episodes.fresh(null, list, now).isEmpty())
        assertTrue(Episodes.fresh(2, list, now).isEmpty())
        val fresh = Episodes.fresh(1, list, now)
        assertEquals(listOf(2), fresh.map { it.episode })
        assertEquals("Вышла 2 серия 1 сезона", Episodes.announce(fresh))
    }
}
