package app.couchy.core

import app.couchy.core.pair.PhoneForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFormTest {
    @Test
    fun parsesFieldsInOrder() {
        val (code, fields) = PhoneForm.parse("c=123456&f0=http%3A%2F%2Fhost%3A8080&f1=%D0%B8%D0%B3%D0%BE%D1%80%D1%8C&f2=a+b")
        assertEquals("123456", code)
        assertEquals(listOf("http://host:8080", "игорь", "a b"), fields)
    }

    @Test
    fun pageEscapesValues() {
        val html = PhoneForm.page("Плейлист", listOf(PhoneForm.Field("Ссылка", value = "\"><script>")), "1")
        assertTrue("&quot;&gt;&lt;script&gt;" in html)
        assertTrue("name=\"f0\"" in html)
    }
}
