package app.couchy.core.youtube

/** A part of a long video: where it starts and what it is called; one saved track each. */
data class Chapter(val startMs: Long, val endMs: Long, val title: String)

object Chapters {
    private val stamp = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])""")

    /**
     * Chapters written in a description, the way YouTube itself reads them: one timestamp per line,
     * the first at 0:00, at least three, each later than the one before. Empty when there are none.
     */
    fun parse(description: String, durationMs: Long): List<Chapter> {
        val marks = ArrayList<Pair<Long, String>>()
        for (line in description.lines()) {
            val m = stamp.find(line) ?: continue
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val ms = ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000
            val title = clean(line.removeRange(m.range))
            marks += ms to title
        }
        if (marks.size < 3 || marks.first().first != 0L) return emptyList()
        if (marks.zipWithNext().any { (a, b) -> b.first <= a.first }) return emptyList()
        if (durationMs > 0 && marks.last().first >= durationMs) return emptyList()
        return of(marks, durationMs)
    }

    /** From start times and names (YouTube's own chapters) to whole chapters with their ends. */
    fun of(marks: List<Pair<Long, String>>, durationMs: Long): List<Chapter> =
        marks.mapIndexed { i, (start, title) ->
            val end = marks.getOrNull(i + 1)?.first ?: durationMs.takeIf { it > start } ?: Long.MAX_VALUE
            Chapter(start, end, title.ifBlank { "Трек ${i + 1}" })
        }

    /** "01. Name.m4a": a file name that sorts in order and is allowed everywhere. */
    fun fileName(number: Int, of: Int, title: String, ext: String): String {
        val width = of.toString().length.coerceAtLeast(2)
        return "${number.toString().padStart(width, '0')}. ${safe(title)}.$ext"
    }

    fun safe(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Без названия" }

    private fun clean(text: String): String {
        val bare = text.replace(Regex("""\[\s*]|\(\s*\)"""), " ").trim().replace(Regex("""^\d{1,3}[.)]\s+"""), "")
        return bare.trim('-', '–', '—', '|', ':', '.', ' ', '\t').trim()
    }
}
