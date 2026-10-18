package app.couchy.ui.player

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What becomes of subtitles in a foreign language. */
enum class SubTranslation(val label: String) {
    OFF("Выключен"),
    RUSSIAN("Только перевод"),
    BOTH("Перевод и оригинал сверху"),
}

/**
 * Subtitles translated into Russian on the device with Google's offline translator. The language
 * model comes down once (about 30 MB) and then works without the internet. Lines already
 * translated are kept, so a whole file can be done ahead and nothing lags behind the picture.
 */
object SubTranslate {
    private val clients = HashMap<String, Translator>()
    private val ready = HashSet<String>()
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4000
    }

    fun mode(context: Context): SubTranslation =
        prefs(context).getString("sub_translate", null)?.let { n -> SubTranslation.entries.firstOrNull { it.name == n } } ?: SubTranslation.OFF

    fun setMode(context: Context, mode: SubTranslation) = prefs(context).edit().putString("sub_translate", mode.name).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** The translator's code for a track language ("en", "eng", "en-US"); English when unknown, null for Russian. */
    fun source(language: String?): String? {
        val tag = language?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotBlank() && it != "und" }
        val two = when (tag) {
            null -> "en"
            "eng" -> "en"
            "rus" -> "ru"
            "ukr" -> "uk"
            "ger", "deu" -> "de"
            "fre", "fra" -> "fr"
            "spa" -> "es"
            "ita" -> "it"
            "jpn" -> "ja"
            "kor" -> "ko"
            "chi", "zho" -> "zh"
            "por" -> "pt"
            "pol" -> "pl"
            "tur" -> "tr"
            else -> tag
        }
        if (two == "ru") return null
        return TranslateLanguage.fromLanguageTag(two) ?: TranslateLanguage.ENGLISH
    }

    /** Whether the model for [from] is already on the device. */
    fun hasModel(from: String) = synchronized(ready) { from in ready }

    /** Brings the model for [from] onto the device; false when it could not be fetched. */
    suspend fun prepare(from: String): Boolean {
        val client = client(from)
        if (hasModel(from)) return true
        return runCatching { client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
            .onSuccess { synchronized(ready) { ready += from } }
            .isSuccess
    }

    /** The Russian for one subtitle [text]; null when the translator is not ready or fails. */
    suspend fun translate(from: String, text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return ""
        val key = "$from|$clean"
        synchronized(cache) { cache[key] }?.let { return it }
        if (!prepare(from)) return null
        // Lines go one by one: the translator is single-threaded anyway.
        val out = lock.withLock {
            synchronized(cache) { cache[key] } ?: runCatching {
                // Line breaks inside a subtitle are joined, the translator reads a sentence better whole.
                client(from).translate(clean.replace('\n', ' ')).await()
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** Translates [lines] ahead of time, so they are ready when they show. */
    suspend fun ahead(from: String, lines: List<String>) {
        if (!prepare(from)) return
        lines.distinct().forEach { translate(from, it) }
    }

    private fun client(from: String): Translator = synchronized(clients) {
        clients.getOrPut(from) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(TranslateLanguage.RUSSIAN).build(),
            )
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
