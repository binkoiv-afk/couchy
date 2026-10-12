package app.couchy.core

import app.couchy.core.VoiceCommand.Companion.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun seeks() {
        assertEquals(VoiceCommand.SeekBy(600_000), parse("Перемотай на 10 минут"))
        assertEquals(VoiceCommand.SeekBy(-30_000), parse("назад на тридцать секунд"))
        assertEquals(VoiceCommand.SeekBy(25 * 60_000L), parse("вперёд двадцать пять минут"))
        assertEquals(VoiceCommand.SeekBy(10_000), parse("перемотай"))
        assertEquals(VoiceCommand.SeekTo(80 * 60_000L), parse("перейди на 1 час 20 минут"))
    }

    @Test
    fun buttons() {
        assertEquals(VoiceCommand.SubtitlesOn, parse("включи субтитры"))
        assertEquals(VoiceCommand.SubtitlesOff, parse("выключи субтитры"))
        assertEquals(VoiceCommand.Next, parse("следующая серия"))
        assertEquals(VoiceCommand.Pause, parse("пауза"))
        assertEquals(VoiceCommand.Play, parse("продолжи"))
        assertEquals(VoiceCommand.Speed(1.5f), parse("скорость полтора"))
        assertEquals(VoiceCommand.Louder, parse("погромче"))
        assertNull(parse("какая погода"))
    }
}
