package app.couchy.core.sources

/** One line of subtitles and when it shows. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** SRT and WebVTT read into cues, for the second line of subtitles Couchy draws itself. */
object SubCues {
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<Cue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) { i++; continue }
            val start = ms(line.substring(0, arrow))
            val end = ms(line.substring(arrow + 3))
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                val clean = lines[i].replace(tags, "").trim()
                if (clean.isNotEmpty()) { if (body.isNotEmpty()) body.append('\n'); body.append(clean) }
                i++
            }
            if (start != null && end != null && end > start && body.isNotEmpty()) cues += Cue(start, end, body.toString())
        }
        return cues.sortedBy { it.startMs }
    }

    /** The text showing at [positionMs], or null between lines. */
    fun at(cues: List<Cue>, positionMs: Long): String? {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        // Overlapping lines show together.
        val showing = (maxOf(0, found - 3)..found).map { cues[it] }.filter { positionMs < it.endMs }
        return showing.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
    }

    private fun ms(part: String): Long? {
        val m = time.find(part) ?: return null
        val (h, min, s, frac) = m.destructured
        return ((h.toLongOrNull() ?: 0) * 3600 + min.toLong() * 60 + s.toLong()) * 1000 + frac.padEnd(3, '0').toLong()
    }
}
