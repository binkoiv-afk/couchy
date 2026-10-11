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
    private val prefs = context.getSharedPreferences("marks", Context.MODE_PRIVATE)
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

    companion object {
        const val LATER = "later"
        const val LOVED = "loved"
        /** Series whose new episodes the person hears about. */
        const val FOLLOW = "follow"
        const val YOUTUBE = "yt"
    }
}

/** The two title lists Home and "Моё" show. */
data class SavedTitles(val later: List<Title> = emptyList(), val loved: List<Title> = emptyList())
