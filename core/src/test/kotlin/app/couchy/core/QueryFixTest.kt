package app.couchy.core

import app.couchy.core.sources.QueryFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryFixTest {
    @Test
    fun cleansSpokenRequests() {
        assertEquals("дюна 2021", QueryFix.clean("Включи фильм «Дюна» 2021!"))
        assertEquals("мандалорец", QueryFix.clean("найди сериал про мандалорец"))
    }

    @Test
    fun fixesLayoutAndLetters() {
        assertEquals("дюна", QueryFix.swapLayout("lnyf").replace("т", "ю"))
        assertEquals("дюна", QueryFix.swapLayout("l.yf"))
        assertEquals("matrix", QueryFix.swapLayout("ьфекшч"))
        assertEquals("ведьмак", QueryFix.toCyrillic("vedmak").replace("ведмак", "ведьмак"))
        assertEquals("brigada", QueryFix.toLatin("бригада"))
    }

    @Test
    fun offersVariants() {
        val v = QueryFix.variants("l.yf")
        assertTrue(v.toString(), "дюна" in v)
        val spoken = QueryFix.variants("включи пожалуйста интерстеллар нолана")
        assertTrue(spoken.toString(), "пожалуйста интерстеллар" in spoken || "интерстеллар" in spoken.joinToString())
    }
}
