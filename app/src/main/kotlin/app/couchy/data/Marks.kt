package app.couchy.data

import android.content.Context
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small personal lists that travel with sync: "Смотреть позже", "Любимое" and the YouTube
 * channels followed. Each entry is a key ("later|…", "loved|…", "yt|…") and its data as JSON.
 */
class Marks(context: Context) {
    private val prefs = context.getSharedPreferences("marks${Profiles.suffix(context)}", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, String>> = _all.asStateFlow()

    private fun load(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("all", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun replaceAll(map: Map<String, String>) {
        prefs.edit().putString("all", JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()).apply()
        _all.value = map
    }

    fun put(key: String, value: JSONObject) = replaceAll(_all.value + (key to value.put("at", System.currentTimeMillis()).toString()))
    fun remove(key: String) = replaceAll(_all.value - key)
    fun has(key: String) = key in _all.value

    /** Entries under one prefix, newest first. */
    fun list(prefix: String): List<JSONObject> = _all.value.filterKeys { it.startsWith("$prefix|") }.values
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .sortedByDescending { it.optLong("at") }

    // ---- Titles ----

    fun titles(list: String): List<Title> = TitleJson.decode(JSONArray(list(list)))

    fun hasTitle(list: String, t: Title) = has("$list|${t.key}")

    fun toggleTitle(list: String, t: Title) {
        val key = "$list|${t.key}"
        if (has(key)) remove(key) else put(key, TitleJson.encode(listOf(t)).getJSONObject(0))
    }

    /** The person's own score, 1 to 10; null takes it away. */
    fun rate(t: Title, score: Int?) {
        val key = "$RATED|${t.key}"
        if (score == null) remove(key) else put(key, TitleJson.encode(listOf(t)).getJSONObject(0).put("score", score.coerceIn(1, 10)))
    }

    /** Scores by title key. */
    fun scores(): Map<String, Int> = _all.value.filterKeys { it.startsWith("$RATED|") }
        .mapNotNull { (k, v) -> runCatching { k.substringAfter('|') to JSONObject(v).getInt("score") }.getOrNull() }.toMap()

    // ---- Folders: the person's own lists of titles ----

    /** Folders, oldest first, so they keep their order. */
    fun folders(): List<Folder> = _all.value.filterKeys { it.startsWith("$FOLDER|") }
        .mapNotNull { (k, v) -> runCatching { JSONObject(v).let { Folder(k.substringAfter('|'), it.getString("name"), it.optLong("at")) } }.getOrNull() }
        .sortedBy { it.at }
        .map { f -> f.copy(titles = titles(inFolder(f.id))) }

    fun addFolder(name: String): String {
        val id = System.currentTimeMillis().toString(36)
        put("$FOLDER|$id", JSONObject().put("name", name.trim()))
        return id
    }

    fun renameFolder(id: String, name: String) {
        val old = _all.value["$FOLDER|$id"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        replaceAll(_all.value + ("$FOLDER|$id" to old.put("name", name.trim()).toString()))
    }

    /** The folder goes with what was put in it; the titles stay everywhere else. */
    fun removeFolder(id: String) = replaceAll(_all.value.filterKeys { it != "$FOLDER|$id" && !it.startsWith(inFolder(id) + "|") })

    fun toggleInFolder(id: String, t: Title) = toggleTitle(inFolder(id), t)

    /** Ids of the folders a title is in. */
    fun foldersOf(t: Title): Set<String> = folders().filter { hasTitle(inFolder(it.id), t) }.map { it.id }.toSet()

    private fun inFolder(id: String) = "$IN_FOLDER$id"

    companion object {
        const val FOLDER = "folder"
        /** Prefix of a folder's entries: "in-<id>|<title key>". */
        const val IN_FOLDER = "in-"
        /** Watched, by the person's own word. */
        const val SEEN = "seen"
        const val RATED = "rated"
        const val LATER = "later"
        const val LOVED = "loved"
        /** Series whose new episodes the person hears about. */
        const val FOLLOW = "follow"
        const val YOUTUBE = "yt"
    }
}

/** A list of titles the person named: "Новый год", "С детьми", "Ужасы на выходные". */
data class Folder(val id: String, val name: String, val at: Long = 0, val titles: List<Title> = emptyList())

/** The two title lists Home and "Моё" show. */
data class SavedTitles(
    val later: List<Title> = emptyList(),
    val loved: List<Title> = emptyList(),
    val seen: List<Title> = emptyList(),
    val rated: List<Title> = emptyList(),
    /** The person's scores by title key. */
    val scores: Map<String, Int> = emptyMap(),
    val folders: List<Folder> = emptyList(),
)
