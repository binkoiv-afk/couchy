package app.couchy.sources

import android.content.Context
import app.couchy.core.sources.Anilibria
import app.couchy.core.sources.KinoPub
import app.couchy.core.sources.NativeRelease
import app.couchy.core.sources.SiteHit
import app.couchy.core.sources.Title
import app.couchy.core.sources.matching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The person's Kino.pub sign-in: tokens kept on the device, refreshed when they run out. */
class KinoPubAccount(context: Context) {
    private val prefs = context.getSharedPreferences("kinopub", Context.MODE_PRIVATE)
    private val web = Web(context)
    private val lock = Mutex()
    private val _signedIn = MutableStateFlow(prefs.getString("refresh", null) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** A code to type on kino.pub/device. */
    suspend fun start(): KinoPub.DeviceCode = KinoPub.parseDeviceCode(web.post("${KinoPub.API}/oauth2/device", KinoPub.deviceCodeForm()))

    /** True once the code was typed; false while it still waits. */
    suspend fun poll(code: String): Boolean {
        val tokens = runCatching { KinoPub.parseTokens(web.post("${KinoPub.API}/oauth2/device", KinoPub.deviceTokenForm(code)), System.currentTimeMillis()) }
            .getOrNull() ?: return false
        save(tokens)
        return true
    }

    /** A working access token, or null when signed out or the sign-in no longer works. */
    suspend fun token(): String? = lock.withLock {
        val refresh = prefs.getString("refresh", null) ?: return null
        val access = prefs.getString("access", null)
        if (access != null && prefs.getLong("until", 0) > System.currentTimeMillis() + 60_000) return access
        val tokens = runCatching { KinoPub.parseTokens(web.post("${KinoPub.API}/oauth2/token", KinoPub.refreshForm(refresh)), System.currentTimeMillis()) }.getOrNull()
        if (tokens == null) return access
        save(tokens)
        tokens.access
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _signedIn.value = false
    }

    private fun save(t: KinoPub.Tokens) {
        prefs.edit().putString("access", t.access).putString("refresh", t.refresh.ifEmpty { prefs.getString("refresh", "") }).putLong("until", t.expiresAt).apply()
        _signedIn.value = true
    }
}

/** AniLibria for everyone, Kino.pub when signed in: found by name or IMDb id, played without a site window. */
class NativeClient(private val web: Web, private val kinopub: KinoPubAccount) {
    private val releases = java.util.concurrent.ConcurrentHashMap<String, NativeRelease>()

    suspend fun find(title: Title): List<SiteHit> = coroutineScope {
        val names = (listOf(title.name) + listOfNotNull(title.original) + title.altNames).distinct()
        val ani = async {
            runCatching {
                // AniLibria is searched by the original name too: its Russian names are often transliterated.
                val hits = names.take(2).flatMap { Anilibria.parseSearch(web.get(Anilibria.searchUrl(it))) }.distinctBy { it.id }
                hits.matching(names, title.year, null)
            }.getOrNull()
        }
        val kp = async {
            runCatching {
                val token = kinopub.token() ?: return@runCatching null
                KinoPub.parseSearch(web.get(KinoPub.searchUrl(title.original ?: title.name, token)))
                    .matching(names, title.year, title.imdbId)
                    ?: KinoPub.parseSearch(web.get(KinoPub.searchUrl(title.name, token))).matching(names, title.year, title.imdbId)
            }.getOrNull()
        }
        listOfNotNull(kp.await(), ani.await()).map { SiteHit(it.source, it.name, it.year, it.id, it.poster) }
    }

    /** The release with its episodes and files; Kino.pub's links are signed, so it is asked again each time. */
    suspend fun release(source: String, id: String): NativeRelease = when (source) {
        Anilibria.ID -> releases.getOrPut("$source:$id") { Anilibria.parseRelease(web.get(Anilibria.releaseUrl(id))) }
        KinoPub.ID -> {
            val token = kinopub.token() ?: throw java.io.IOException("Войдите в Kino.pub в настройках: «Источники и инфо».")
            KinoPub.parseItem(web.get(KinoPub.itemUrl(id, token)))
        }
        else -> throw IllegalArgumentException(source)
    }

    companion object {
        val ids = setOf(Anilibria.ID, KinoPub.ID)
        fun name(id: String) = when (id) {
            Anilibria.ID -> Anilibria.NAME
            KinoPub.ID -> KinoPub.NAME
            else -> null
        }
    }
}
