package app.couchy.core

/** What a link or file points at, which decides how Couchy opens it. */
enum class LinkKind {
    /** A plain video file or progressive stream: mp4, mkv, webm and the like. */
    VIDEO,
    /** HLS stream (.m3u8). */
    HLS,
    /** MPEG-DASH stream (.mpd). */
    DASH,
    /** An IPTV playlist (.m3u) to import as channels, not to play. */
    PLAYLIST,
    /** A magnet link or .torrent file, for the torrent engine. */
    TORRENT,
    /** A YouTube page. */
    YOUTUBE,
    /** Anything else; the player tries it and reports if it cannot play it. */
    UNKNOWN,
}

object Links {
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mts", "wmv", "flv", "3gp", "mpg", "mpeg", "vob",
    )

    fun classify(link: String): LinkKind {
        val trimmed = link.trim()
        val lower = trimmed.lowercase()
        if (lower.startsWith("magnet:")) return LinkKind.TORRENT
        val host = hostOf(lower)
        if (host != null && (host == "youtu.be" || host.endsWith("youtube.com"))) return LinkKind.YOUTUBE
        return when (extensionOf(lower)) {
            "m3u8" -> LinkKind.HLS
            "mpd" -> LinkKind.DASH
            "m3u" -> LinkKind.PLAYLIST
            "torrent" -> LinkKind.TORRENT
            in videoExtensions -> LinkKind.VIDEO
            else -> LinkKind.UNKNOWN
        }
    }

    /** Whether the built-in player can be handed this link as is. */
    fun isPlayable(kind: LinkKind): Boolean =
        kind == LinkKind.VIDEO || kind == LinkKind.HLS || kind == LinkKind.DASH || kind == LinkKind.UNKNOWN

    /** True for text that looks like something Couchy can open: a URL, a magnet or a content/file uri. */
    fun looksLikeLink(text: String): Boolean {
        val lower = text.trim().lowercase()
        return listOf("http://", "https://", "magnet:", "rtsp://", "rtmp://", "udp://", "content://", "file://")
            .any { lower.startsWith(it) }
    }

    /**
     * A readable title for a link with no metadata: the file name without extension, with
     * dots and underscores turned into spaces, or the host for bare URLs.
     */
    fun titleFor(link: String): String {
        val trimmed = link.trim()
        if (trimmed.lowercase().startsWith("magnet:")) {
            val name = Regex("[?&]dn=([^&]+)").find(trimmed)?.groupValues?.get(1)
            return name?.let { decode(it) }?.let(::tidy) ?: "Торрент"
        }
        val path = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = decode(path.substringAfterLast('/'))
        val base = if ('.' in last) last.substringBeforeLast('.') else last
        val tidy = tidy(base)
        if (tidy.isNotBlank() && !path.endsWith("//" + last)) return tidy
        return hostOf(trimmed.lowercase()) ?: trimmed
    }

    private fun tidy(name: String): String =
        name.replace('_', ' ').replace(Regex("(?<=\\S)\\.(?=\\S)"), " ").replace(Regex("\\s+"), " ").trim()

    private fun extensionOf(lower: String): String? {
        val path = lower.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if ('.' !in last) return null
        return last.substringAfterLast('.')
    }

    private fun hostOf(lower: String): String? {
        val scheme = lower.indexOf("://")
        if (scheme < 0) return null
        return lower.substring(scheme + 3).substringBefore('/').substringBefore('?').substringBefore(':')
            .removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    private fun decode(text: String): String =
        try {
            java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }
}
