package app.couchy.core.iptv

/** A channel line from an M3U playlist. */
data class M3uChannel(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    /** tvg-id: the channel's id in the EPG. */
    val tvgId: String?,
    /** tvg-name, sometimes used for EPG matching when there is no id. */
    val tvgName: String?,
    val catchup: Catchup?,
    val userAgent: String?,
    val referrer: String?,
)

data class M3uPlaylist(val channels: List<M3uChannel>, val epgUrls: List<String>)

/**
 * Parses extended M3U as IPTV providers write it: #EXTINF attributes (tvg-id, tvg-logo,
 * group-title, catchup, catchup-source, catchup-days), #EXTGRP and #EXTVLCOPT lines, and the
 * EPG address in the #EXTM3U header (url-tvg / x-tvg-url).
 */
object M3u {
    private val attribute = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun parse(text: String): M3uPlaylist {
        val channels = mutableListOf<M3uChannel>()
        val epg = mutableListOf<String>()
        var info: Map<String, String> = emptyMap()
        var name: String? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var playlistCatchup: Map<String, String> = emptyMap()

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").mapNotNull { attrs[it] }
                        .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { if (it !in epg) epg += it }
                    playlistCatchup = attrs.filterKeys { it.startsWith("catchup") }
                }
                line.startsWith("#EXTINF") -> {
                    info = attributes(line.substringBefore(',', line))
                    name = afterAttributesComma(line)
                    group = null; userAgent = null; referrer = null
                }
                line.startsWith("#EXTGRP:") -> group = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val option = line.removePrefix("#EXTVLCOPT:")
                    when {
                        option.startsWith("http-user-agent=") -> userAgent = option.substringAfter('=')
                        option.startsWith("http-referrer=") || option.startsWith("http-referer=") -> referrer = option.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val title = name?.takeIf { it.isNotBlank() } ?: info["tvg-name"] ?: line.substringAfterLast('/')
                    channels += M3uChannel(
                        name = title.trim(),
                        url = line,
                        group = (info["group-title"] ?: group)?.takeIf { it.isNotBlank() },
                        logo = info["tvg-logo"]?.takeIf { it.isNotBlank() },
                        tvgId = info["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = info["tvg-name"]?.takeIf { it.isNotBlank() },
                        catchup = Catchup.from(
                            type = info["catchup"] ?: info["catchup-type"] ?: playlistCatchup["catchup"],
                            source = info["catchup-source"] ?: playlistCatchup["catchup-source"],
                            days = (info["catchup-days"] ?: info["tvg-rec"] ?: playlistCatchup["catchup-days"])?.toIntOrNull(),
                        ),
                        userAgent = userAgent,
                        referrer = referrer,
                    )
                    info = emptyMap(); name = null; group = null; userAgent = null; referrer = null
                }
            }
        }
        return M3uPlaylist(channels, epg)
    }

    fun looksLikeM3u(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")

    private fun attributes(line: String): Map<String, String> =
        attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The title is after the first comma that is not inside a quoted attribute. */
    private fun afterAttributesComma(line: String): String? {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return line.substring(i + 1).trim()
            }
        }
        return null
    }
}
