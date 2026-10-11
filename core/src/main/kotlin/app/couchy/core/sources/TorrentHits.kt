package app.couchy.core.sources

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** One torrent found for a title. */
data class TorrentHit(
    val title: String,
    val magnet: String,
    val hash: String,
    val size: Long,
    val seeds: Int,
    val tracker: String,
    /** 2160, 1080, 720, 480, or 0 when unknown. */
    val quality: Int,
    val voices: List<String> = emptyList(),
    val hdr: Boolean = false,
    val seasons: List<Int> = emptyList(),
)

/** A quality group on the title page. */
data class TorrentGroup(val label: String, val hits: List<TorrentHit>)

object TorrentHits {
    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "http://retracker.local/announce",
    )

    fun magnet(hash: String, name: String): String =
        "magnet:?xt=urn:btih:$hash&dn=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    fun qualityOf(name: String): Int {
        val n = name.lowercase()
        return when {
            Regex("2160p|\\b4k\\b|\\buhd\\b").containsMatchIn(n) -> 2160
            "1080" in n -> 1080
            "720" in n -> 720
            Regex("480p|dvdrip|\\bsd\\b|\\bts\\b|\\btc\\b|camrip|telesync|telecine").containsMatchIn(n) -> 480
            else -> 0
        }
    }

    private fun hdrOf(name: String) = Regex("\\bhdr|\\bdv\\b|dolby vision", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** Jacred: a JSON array of torrents from Russian trackers, with quality and voices already parsed. */
    fun parseJacred(json: String): List<TorrentHit> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val magnet = o.optString("magnet").takeIf { it.startsWith("magnet:") } ?: return@mapNotNull null
            val hash = Regex("btih:([a-zA-Z0-9]+)").find(magnet)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
            val title = o.optString("title")
            TorrentHit(
                title = title,
                magnet = magnet,
                hash = hash,
                size = o.optLong("size"),
                seeds = o.optInt("sid"),
                tracker = o.optString("tracker"),
                quality = o.optInt("quality").takeIf { it > 0 } ?: qualityOf(title),
                voices = o.optJSONArray("voices")?.let { v -> (0 until v.length()).map { v.getString(it) } }.orEmpty(),
                hdr = o.optString("videotype") == "hdr" || hdrOf(title),
                seasons = o.optJSONArray("seasons")?.let { v -> (0 until v.length()).map { v.optInt(it) } }.orEmpty(),
            )
        }
    }

    /** Torrentio (Stremio add-on): streams by IMDb id from international trackers. */
    fun parseTorrentio(json: String): List<TorrentHit> {
        val a = JSONObject(json).optJSONArray("streams") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val hash = o.optString("infoHash").lowercase().takeIf { it.length == 40 } ?: return@mapNotNull null
            val lines = o.optString("title").lines()
            val name = lines.first()
            val meta = lines.firstOrNull { "👤" in it || "💾" in it }.orEmpty()
            val seeds = Regex("👤\\s*(\\d+)").find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val size = Regex("💾\\s*([\\d.]+)\\s*(TB|GB|MB|KB)").find(meta)?.let { m ->
                val v = m.groupValues[1].toDouble()
                val mul = when (m.groupValues[2]) { "TB" -> 1L shl 40; "GB" -> 1L shl 30; "MB" -> 1L shl 20; else -> 1L shl 10 }
                (v * mul).toLong()
            } ?: 0
            val tracker = Regex("⚙️\\s*(.+)").find(meta)?.groupValues?.get(1)?.trim() ?: "Torrentio"
            val label = o.optString("name")
            TorrentHit(
                title = name,
                magnet = magnet(hash, name),
                hash = hash,
                size = size,
                seeds = seeds,
                tracker = tracker,
                quality = when {
                    "4k" in label.lowercase() || "2160" in label -> 2160
                    "1080" in label -> 1080
                    "720" in label -> 720
                    "480" in label -> 480
                    else -> qualityOf(name)
                },
                hdr = "HDR" in label || "DV" in label || hdrOf(name),
            )
        }
    }

    /**
     * One list for the title page: duplicates (same hash from two sources) merged, dead
     * torrents last, then grouped by quality from best, each group by seeds.
     */
    fun group(hits: List<TorrentHit>): List<TorrentGroup> {
        val unique = hits.groupBy { it.hash }.map { (_, same) ->
            val best = same.maxBy { it.seeds }
            best.copy(voices = same.flatMap { it.voices }.distinct())
        }
        val order = listOf(2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "SD", 0 to "Другое")
        return order.mapNotNull { (q, label) ->
            val list = unique.filter { if (q == 0) it.quality !in setOf(2160, 1080, 720, 480) else it.quality == q }
                .sortedWith(compareByDescending<TorrentHit> { it.seeds > 0 }.thenByDescending { it.seeds })
            if (list.isEmpty()) null else TorrentGroup(label, list)
        }
    }
}
