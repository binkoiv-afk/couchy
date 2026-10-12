package app.couchy.core

/** What can be said to the player: "перемотай на 10 минут", "включи субтитры", "следующая серия". */
sealed interface VoiceCommand {
    data class SeekBy(val ms: Long) : VoiceCommand
    data class SeekTo(val ms: Long) : VoiceCommand
    data object Pause : VoiceCommand
    data object Play : VoiceCommand
    data object Next : VoiceCommand
    data object SubtitlesOn : VoiceCommand
    data object SubtitlesOff : VoiceCommand
    data object Louder : VoiceCommand
    data object Quieter : VoiceCommand
    data object NightSound : VoiceCommand
    data class Speed(val value: Float) : VoiceCommand
    data object Exit : VoiceCommand

    companion object {
        private val units = mapOf(
            "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5,
            "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
            "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
            "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        )

        /** "двадцать пять" → 25, "15" → 15; words that are not numbers are left as they are. */
        private fun numbers(text: String): String {
            val out = ArrayList<String>()
            for (w in text.split(' ').filter { it.isNotBlank() }) {
                val n = units[w]
                val prev = out.lastOrNull()?.toIntOrNull()
                if (n != null && prev != null && prev >= 20 && prev % 10 == 0 && n in 1..9) out[out.size - 1] = (prev + n).toString()
                else out += n?.toString() ?: w
            }
            return out.joinToString(" ")
        }

        /** Hours, minutes and seconds said in [text], or null when no time is named. */
        private fun duration(text: String): Long? {
            var ms = 0L
            var found = false
            Regex("""(?U)(\d+)\s*(час|ч\b)""").find(text)?.let { ms += it.groupValues[1].toLong() * 3_600_000; found = true }
            Regex("""(?U)(\d+)\s*(минут|мин\b)""").find(text)?.let { ms += it.groupValues[1].toLong() * 60_000; found = true }
            Regex("""(?U)(\d+)\s*(секунд|сек\b)""").find(text)?.let { ms += it.groupValues[1].toLong() * 1_000; found = true }
            if (!found) {
                when {
                    "полчаса" in text -> { ms = 1_800_000; found = true }
                    Regex("""(?U)\bминуту\b""").containsMatchIn(text) -> { ms = 60_000; found = true }
                    Regex("""(?U)\bчас\b""").containsMatchIn(text) -> { ms = 3_600_000; found = true }
                }
            }
            return ms.takeIf { found }
        }

        fun parse(said: String): VoiceCommand? {
            val t = numbers(said.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\d ]"), " ").replace(Regex("\\s+"), " ").trim())
            if (t.isEmpty()) return null
            val off = Regex("""(?U)\b(выключи|убери|отключи|без)\b""").containsMatchIn(t)
            return when {
                "субтитр" in t -> if (off) SubtitlesOff else SubtitlesOn
                "ночн" in t -> NightSound
                "заставк" in t || "интро" in t -> SeekBy(85_000)
                Regex("""(?U)след\w* сери|\bдальше\b""").containsMatchIn(t) -> Next
                "скорост" in t -> when {
                    Regex("""(?U)норм|обычн|\b1\b""").containsMatchIn(t) -> Speed(1f)
                    "полтор" in t -> Speed(1.5f)
                    Regex("""(?U)\b2\b|двойн""").containsMatchIn(t) -> Speed(2f)
                    else -> Speed(1.25f)
                }
                Regex("""(?U)громче|погромче""").containsMatchIn(t) -> Louder
                Regex("""(?U)тише|потише""").containsMatchIn(t) -> Quieter
                Regex("""(?U)\b(выход|выйди|закрой)\b""").containsMatchIn(t) -> Exit
                // "перейди на 1 час 20 минут", "включи с 45 минуты": a place in the film.
                Regex("""(?U)\b(перейди|переключи|включи с|начни с|с)\b""").containsMatchIn(t) && !Regex("""(?U)вперед|назад""").containsMatchIn(t) ->
                    duration(t)?.let { SeekTo(it) } ?: if ("включи" in t) Play else null
                Regex("""(?U)перемот|вперед|назад|отмот|промот|верни""").containsMatchIn(t) -> {
                    val back = Regex("""(?U)назад|отмот|верни""").containsMatchIn(t)
                    val ms = duration(t) ?: 10_000
                    SeekBy(if (back) -ms else ms)
                }
                Regex("""(?U)\b(пауза|стоп|останови|постой|подожди)\b""").containsMatchIn(t) -> Pause
                Regex("""(?U)\b(играй|продолжи|продолжай|плей|смотрим|включи|запусти)\b""").containsMatchIn(t) -> Play
                else -> null
            }
        }
    }
}
