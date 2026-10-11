package app.couchy.core.torrent

import java.util.Locale

/** One file inside a torrent, as the engine lists it. */
data class TorrFile(val id: Int, val path: String, val length: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Season and episode found in a file name. */
data class Episode(val season: Int?, val episode: Int)

object Magnet {
    private val btih = Regex("xt=urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)

    /** The info hash of a magnet link in lowercase hex, or null when there is none. */
    fun hash(link: String): String? {
        val raw = btih.find(link)?.groupValues?.get(1) ?: return null
        return when (raw.length) {
            40 -> raw.lowercase().takeIf { it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            32 -> base32ToHex(raw.uppercase())
            else -> null
        }
    }

    /** A bare magnet for a hash, used when the original link was a local .torrent file. */
    fun fromHash(hash: String, name: String?): String =
        "magnet:?xt=urn:btih:$hash" + (name?.takeIf { it.isNotBlank() }?.let { "&dn=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "")

    private fun base32ToHex(text: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (c in text) {
            val v = alphabet.indexOf(c).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            while (bits >= 4) {
                bits -= 4
                out.append("0123456789abcdef"[((buffer shr bits) and 0xF).toInt()])
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}

/**
 * The library remembers each watched file of a torrent as its own entry: the torrent link
 * plus the file number, so progress and "continue" work per episode.
 */
object TorrentRef {
    private const val MARK = "#couchy-file="

    fun of(link: String, fileId: Int): String = link.substringBefore(MARK) + MARK + fileId

    /** The torrent link and file number of a reference; the number is null for a plain link. */
    fun parse(ref: String): Pair<String, Int?> {
        val at = ref.lastIndexOf(MARK)
        if (at < 0) return ref to null
        return ref.substring(0, at) to ref.substring(at + MARK.length).toIntOrNull()
    }
}

object TorrFiles {
    private val video = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "m2ts", "mts", "wmv", "webm", "mpg", "mpeg", "vob", "flv", "3gp")
    private val audio = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")

    fun isVideo(path: String) = path.substringAfterLast('.', "").lowercase() in video

    /**
     * What is worth showing: video files (and music when there is no video), in natural order,
     * so "Серия 2" comes before "Серия 10". Samples and extras under 1/20 of the largest file
     * are dropped when there is more than one file.
     */
    fun playable(files: List<TorrFile>): List<TorrFile> {
        val videos = files.filter { isVideo(it.path) }
        val pick = videos.ifEmpty { files.filter { it.path.substringAfterLast('.', "").lowercase() in audio } }
        val biggest = pick.maxOfOrNull { it.length } ?: 0
        val main = if (pick.size > 1) pick.filter { it.length >= biggest / 20 && !it.name.contains("sample", ignoreCase = true) } else pick
        return main.sortedWith { a, b -> natural(a.path, b.path) }
    }

    /** The file after [id] in [playable] order, for autoplaying the next episode. */
    fun next(files: List<TorrFile>, id: Int): TorrFile? {
        val list = playable(files)
        val i = list.indexOfFirst { it.id == id }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }

    private val sxe = Regex("""[Ss](\d{1,2})[ ._-]?[Ee](\d{1,3})""")
    private val nxn = Regex("""(?<!\d)(\d{1,2})x(\d{1,3})(?!\d)""")
    private val ru = Regex("""(?:[Сс]ерия|[Ss]eries|[Ee]pisode|[Ee]p)[ ._-]?(\d{1,3})""")
    private val leading = Regex("""^(\d{1,3})(?!\d)[ ._-]""")

    fun episodeOf(path: String): Episode? {
        val name = path.substringAfterLast('/')
        sxe.find(name)?.let { return Episode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        nxn.find(name)?.let { return Episode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        ru.find(name)?.let { return Episode(null, it.groupValues[1].toInt()) }
        leading.find(name)?.let { return Episode(null, it.groupValues[1].toInt()) }
        return null
    }

    /** A short label for a file in the list: "Серия 3" or "S2 · Серия 5", else the tidy file name. */
    fun label(file: TorrFile, single: Boolean): String {
        if (!single) episodeOf(file.path)?.let { e ->
            return (e.season?.let { "S$it · " } ?: "") + "Серия ${e.episode}"
        }
        return tidy(file.name)
    }

    /** The library title for a watched file: torrent name, plus the episode for series. */
    fun title(torrent: String, file: TorrFile, single: Boolean): String {
        val base = tidy(torrent).ifBlank { tidy(file.name) }
        if (single) return base
        val e = episodeOf(file.path) ?: return "$base · ${tidy(file.name)}"
        return "$base · " + (e.season?.let { "S${it}E${e.episode}" } ?: "Серия ${e.episode}")
    }

    fun tidy(name: String): String =
        name.substringBeforeLast('.').let { if (it.isEmpty()) name else it }
            .replace('_', ' ').replace(Regex("(?<=\\S)\\.(?=\\S)"), " ").replace(Regex("\\s+"), " ").trim()

    /** Compares names with digit runs as numbers. */
    fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i; while (i < a.length && a[i].isDigit()) i++
                val sj = j; while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object Sizes {
    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> fmt(n / (1L shl 30).toDouble()) + " ГБ"
        n >= 1L shl 20 -> fmt(n / (1L shl 20).toDouble()) + " МБ"
        n >= 1L shl 10 -> "${n shr 10} КБ"
        else -> "$n Б"
    }

    fun speed(bytesPerSecond: Double): String = when {
        bytesPerSecond >= 1 shl 20 -> fmt(bytesPerSecond / (1 shl 20)) + " МБ/с"
        else -> "${(bytesPerSecond / 1024).toInt()} КБ/с"
    }

    private fun fmt(v: Double) = if (v >= 100) "${v.toInt()}" else String.format(Locale.US, "%.1f", v).replace('.', ',')
}
