package app.couchy.core.sync

import org.json.JSONArray
import org.json.JSONObject

/** One library title as it travels between devices; [key] is the library link. */
data class SyncItem(
    val key: String,
    val title: String,
    val kind: String,
    val addedAt: Long,
    val lastWatchedAt: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val favorite: Boolean = false,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    /** Shown in kids mode. */
    val kids: Boolean = false,
)

/** An IPTV playlist, the same on every device; channels and guide are loaded by each device itself. */
data class SyncPlaylist(
    val name: String,
    val kind: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epg: String? = null,
) {
    val key: String get() = "$kind|${url.trim().trimEnd('/')}|${username.orEmpty()}"
}

data class SyncState(
    val items: Map<String, SyncItem> = emptyMap(),
    val playlists: Map<String, SyncPlaylist> = emptyMap(),
    /** Sites found blocked, so every device opens them through the bypass. */
    val bypassHosts: Set<String> = emptySet(),
    /**
     * Small lists kept as sets: "later|<title>", "loved|<title>", "yt|<channel>" with their data
     * as JSON text. Adding and removing travels like playlists do.
     */
    val marks: Map<String, String> = emptyMap(),
) {
    fun toJson(): String {
        val items = JSONArray()
        this.items.values.sortedBy { it.key }.forEach { i ->
            items.put(
                JSONObject().put("key", i.key).put("title", i.title).put("kind", i.kind).put("addedAt", i.addedAt)
                    .put("lastWatchedAt", i.lastWatchedAt).put("positionMs", i.positionMs).put("durationMs", i.durationMs)
                    .put("favorite", i.favorite).put("kids", i.kids).putOpt("audio", i.audioLanguage).putOpt("subs", i.subtitleLanguage),
            )
        }
        val lists = JSONArray()
        playlists.values.sortedBy { it.key }.forEach { p ->
            lists.put(
                JSONObject().put("name", p.name).put("kind", p.kind).put("url", p.url)
                    .putOpt("username", p.username).putOpt("password", p.password).putOpt("epg", p.epg),
            )
        }
        return JSONObject().put("version", 1).put("items", items).put("playlists", lists)
            .put("bypassHosts", JSONArray(bypassHosts.sorted()))
            .put("marks", JSONObject().apply { marks.toSortedMap().forEach { (k, v) -> put(k, v) } }).toString(1)
    }

    companion object {
        fun fromJson(text: String): SyncState {
            val o = JSONObject(text)
            val items = o.optJSONArray("items") ?: JSONArray()
            val lists = o.optJSONArray("playlists") ?: JSONArray()
            val hosts = o.optJSONArray("bypassHosts") ?: JSONArray()
            fun JSONObject.str(name: String) = if (has(name) && !isNull(name)) getString(name) else null
            return SyncState(
                items = (0 until items.length()).map { items.getJSONObject(it) }.map { i ->
                    SyncItem(
                        i.getString("key"), i.optString("title"), i.optString("kind"), i.optLong("addedAt"),
                        i.optLong("lastWatchedAt"), i.optLong("positionMs"), i.optLong("durationMs"), i.optBoolean("favorite"),
                        i.str("audio"), i.str("subs"), i.optBoolean("kids"),
                    )
                }.associateBy { it.key },
                playlists = (0 until lists.length()).map { lists.getJSONObject(it) }.map { p ->
                    SyncPlaylist(p.getString("name"), p.getString("kind"), p.getString("url"), p.str("username"), p.str("password"), p.str("epg"))
                }.associateBy { it.key },
                bypassHosts = (0 until hosts.length()).map { hosts.getString(it) }.toSet(),
                marks = o.optJSONObject("marks")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
            )
        }
    }
}

object LibrarySync {
    /**
     * Three-way merge. [base] is what this device and the cloud agreed on last time: an item
     * that was there and is now gone on one side was deleted there, unless the other side
     * watched it since.
     */
    fun merge(base: SyncState, local: SyncState, remote: SyncState): SyncState = SyncState(
        items = mergeItems(base.items, local.items, remote.items),
        playlists = mergeSets(base.playlists, local.playlists, remote.playlists),
        bypassHosts = mergeSets(base.bypassHosts.associateWith { it }, local.bypassHosts.associateWith { it }, remote.bypassHosts.associateWith { it }).keys,
        marks = mergeSets(base.marks, local.marks, remote.marks),
    )

    private fun mergeItems(base: Map<String, SyncItem>, local: Map<String, SyncItem>, remote: Map<String, SyncItem>): Map<String, SyncItem> =
        (local.keys + remote.keys).mapNotNull { key ->
            val l = local[key]
            val r = remote[key]
            val b = base[key]
            when {
                l != null && r != null -> key to item(b, l, r)
                l != null -> (key to l).takeIf { b == null || l.lastWatchedAt > b.lastWatchedAt }
                r != null -> (key to r).takeIf { b == null || r.lastWatchedAt > b.lastWatchedAt }
                else -> null
            }
        }.toMap()

    private fun item(b: SyncItem?, l: SyncItem, r: SyncItem): SyncItem {
        // Where you stopped and the tracks come from whoever watched last.
        val watched = if (r.lastWatchedAt > l.lastWatchedAt) r else l
        // A favourite mark changed on one side wins over the side that left it alone.
        val favorite = when {
            b == null -> l.favorite || r.favorite
            l.favorite != b.favorite -> l.favorite
            else -> r.favorite
        }
        val kids = when {
            b == null -> l.kids || r.kids
            l.kids != b.kids -> l.kids
            else -> r.kids
        }
        return watched.copy(
            kids = kids,
            title = watched.title.ifBlank { l.title },
            addedAt = minOf(l.addedAt, r.addedAt).takeIf { it > 0 } ?: maxOf(l.addedAt, r.addedAt),
            favorite = favorite,
        )
    }

    private fun <T> mergeSets(base: Map<String, T>, local: Map<String, T>, remote: Map<String, T>): Map<String, T> =
        (local.keys + remote.keys).mapNotNull { key ->
            val l = local[key]
            val r = remote[key]
            when {
                l != null && r != null -> key to l
                // Gone from the other side after both had it: removed there.
                key in base -> null
                else -> key to (l ?: r)!!
            }
        }.toMap()

    /** What changed for this device: items to write and keys to delete. */
    fun changes(local: SyncState, merged: SyncState): Pair<List<SyncItem>, Set<String>> =
        merged.items.values.filter { it != local.items[it.key] } to (local.items.keys - merged.items.keys)
}
