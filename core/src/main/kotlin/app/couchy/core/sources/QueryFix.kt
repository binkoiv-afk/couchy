package app.couchy.core.sources

/**
 * Other ways to read a search that found nothing: spoken requests ("включи фильм про дюну"),
 * text typed in the wrong keyboard layout ("lzyf"), Latin letters for a Russian name and back.
 */
object QueryFix {
    private val filler = setOf(
        "фильм", "фильмы", "кино", "сериал", "сериалы", "мультик", "мультики", "мультфильм", "аниме",
        "смотреть", "посмотреть", "включи", "включить", "найди", "найти", "покажи", "поставь", "хочу",
        "онлайн", "бесплатно", "хорошем", "качестве", "все", "серии", "сезон", "сезоны", "про", "о", "об", "в", "на",
        "movie", "film", "series", "watch", "online",
    )

    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    private val latin = mapOf(
        "shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч", "sh" to "ш", "yu" to "ю", "ya" to "я", "yo" to "ё",
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д", "e" to "е", "z" to "з", "i" to "и", "j" to "й", "k" to "к",
        "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с", "t" to "т", "u" to "у", "f" to "ф",
        "h" to "х", "c" to "к", "y" to "ы", "w" to "в", "x" to "кс", "q" to "к",
    )
    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y",
        'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** "Включи фильм Дюна 2021!" → "дюна 2021". */
    fun clean(q: String): String {
        val words = q.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}\s-]"""), " ").split(Regex("""\s+""")).filter { it.isNotBlank() }
        val kept = words.filter { it !in filler }
        return (kept.ifEmpty { words }).joinToString(" ")
    }

    /** Typed with the other keyboard layout: "lyf" → "дна", "ассщ" → "fffo". */
    fun swapLayout(q: String): String = q.lowercase().map { ch ->
        val i = EN.indexOf(ch)
        val j = RU.indexOf(ch)
        when {
            i >= 0 -> RU[i]
            j >= 0 -> EN[j]
            else -> ch
        }
    }.joinToString("")

    fun toCyrillic(q: String): String {
        var s = q.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val hit = (4 downTo 1).firstNotNullOfOrNull { n -> s.substring(i, minOf(s.length, i + n)).takeIf { it.length == n }?.let { latin[it]?.let { r -> n to r } } }
            if (hit != null) { out.append(hit.second); i += hit.first } else { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    fun toLatin(q: String): String = q.lowercase().map { cyrillic[it] ?: it.toString() }.joinToString("")

    /** What to try, in order, after [q] found nothing; the query itself is not repeated. */
    fun variants(q: String): List<String> {
        val base = clean(q)
        val list = mutableListOf(base)
        val hasLatin = base.any { it in 'a'..'z' }
        val hasCyr = base.any { it in 'а'..'я' }
        // The layout swap works on the raw text: ".", ",", ";" and "[" are Russian letters there.
        val raw = q.trim().lowercase()
        val rawLatin = raw.any { it in 'a'..'z' }
        val rawCyr = raw.any { it in 'а'..'я' || it == 'ё' }
        if (rawLatin != rawCyr) list += clean(swapLayout(raw))
        if (hasLatin && !hasCyr) list += toCyrillic(base)
        if (hasCyr && !hasLatin) list += toLatin(base)
        // A long spoken phrase: its first words are usually the name.
        val words = base.split(' ')
        if (words.size > 2) list += words.take(2).joinToString(" ")
        if (words.size > 1) list += words.first()
        return list.map { it.trim() }.filter { it.length >= 2 }.distinct().filter { it != q.trim().lowercase() }
    }
}
