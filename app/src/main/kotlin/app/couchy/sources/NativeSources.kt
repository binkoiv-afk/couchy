package app.couchy.sources

import android.content.Context
import app.couchy.core.sources.Anilibria
import app.couchy.core.sources.Filmix
import app.couchy.core.sources.KinoPub
import app.couchy.core.sources.NativeEpisode
import app.couchy.core.sources.NativeRelease
import app.couchy.core.sources.NativeVoice
import app.couchy.core.sources.Rutube
import app.couchy.core.sources.Seasonvar
import app.couchy.core.sources.TitleKind
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

/**
 * Filmix by the API of its own app: works signed out in the lower qualities; signing in with a
 * code typed on filmix's «Консоли» page opens the rest. The token stays on the device.
 */
class FilmixAccount(context: Context) {
    private val prefs = context.getSharedPreferences("filmix", Context.MODE_PRIVATE)
    private val web = Web(context)
    private val _signedIn = MutableStateFlow(prefs.getString("token", null) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** A device id made once, as Filmix's own app does. */
    val devId: String = prefs.getString("dev", null) ?: (1..16).map { "0123456789abcdef".random() }.joinToString("").also { prefs.edit().putString("dev", it).apply() }
    val token: String get() = prefs.getString("token", null).orEmpty()

    /** The API address that answered last; the next one is tried when it stops. */
    suspend fun <T> call(block: suspend (api: String) -> T): T {
        val last = prefs.getString("api", null)
        val order = (listOfNotNull(last) + Filmix.apis).distinct()
        var error: Throwable? = null
        for (api in order) {
            try {
                return block(api).also { if (api != last) prefs.edit().putString("api", api).apply() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            }
        }
        throw error ?: java.io.IOException("Filmix не ответил")
    }

    suspend fun start(): Filmix.DeviceCode = call { api -> Filmix.parseDeviceCode(web.get(Filmix.tokenRequestUrl(api, devId))) }

    /** True once the code was typed on the «Консоли» page. */
    suspend fun poll(code: Filmix.DeviceCode): Boolean {
        val ok = runCatching { call { api -> Filmix.parseSignedIn(web.get(Filmix.profileUrl(api, devId, code.token))) } }.getOrDefault(false)
        if (ok) {
            prefs.edit().putString("token", code.token).apply()
            _signedIn.value = true
        }
        return ok
    }

    fun signOut() {
        prefs.edit().remove("token").apply()
        _signedIn.value = false
    }
}

/** AniLibria for everyone, Kino.pub when signed in: found by name or IMDb id, played without a site window. */
class NativeClient(private val web: Web, private val kinopub: KinoPubAccount, private val filmix: FilmixAccount) {
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
        val fx = async {
            runCatching {
                filmix.call { api ->
                    Filmix.parseSearch(web.get(Filmix.searchUrl(api, title.name, filmix.devId, filmix.token))).matching(names, title.year, title.imdbId)
                        ?: title.original?.let { Filmix.parseSearch(web.get(Filmix.searchUrl(api, it, filmix.devId, filmix.token))).matching(names, title.year, title.imdbId) }
                }
            }.getOrNull()
        }
        // Seasonvar keeps series only, and gives no years: the name has to match exactly.
        val sv = async {
            if (title.kind != TitleKind.SERIES) return@async null
            runCatching {
                names.take(2).firstNotNullOfOrNull { n -> Seasonvar.parseSearch(web.get(Seasonvar.searchUrl(n), mapOf("X-Requested-With" to "XMLHttpRequest"))).matching(names, null, null) }
            }.getOrNull()
        }
        val rt = async { runCatching { rutube(title, names) }.getOrNull() }
        listOfNotNull(kp.await(), fx.await(), sv.await(), ani.await(), rt.await()).map { SiteHit(it.source, it.name, it.year, it.id, it.poster) }
    }

    /** Rutube: a full-length film, or episodes found by the series' name; null when there is neither. */
    private suspend fun rutube(title: Title, names: List<String>): NativeRelease? {
        val series = title.kind == TitleKind.SERIES
        val query = if (series) title.name else listOfNotNull(title.name, title.year).joinToString(" ")
        val videos = Rutube.parseSearch(web.get(Rutube.searchUrl(query)))
        return if (series) {
            if (Rutube.episodes(videos, names).size < 2) return null
            NativeRelease(Rutube.ID, "q" + java.net.URLEncoder.encode(title.name, "UTF-8").replace("+", "%20"), title.name, null, title.year, true, null)
        } else {
            val v = Rutube.film(videos, names, title.year) ?: return null
            // Kept with the film's own name, so the library does not call it "Rutube".
            NativeRelease(Rutube.ID, "v" + v.id, title.name, null, title.year, false, v.thumb, title.imdbId, listOf(NativeEpisode(0, 0, title.name, listOf("Авто" to Rutube.optionsUrl(v.id)))))
                .also { releases["${Rutube.ID}:v${v.id}"] = it }
        }
    }

    /** Files ready to play: Rutube's are asked for their HLS link only now. */
    suspend fun files(e: NativeEpisode): List<Pair<String, String>> = e.files.map { (q, url) ->
        if (url.startsWith(Rutube.OPTIONS)) {
            val m3u8 = Rutube.parseOptions(web.get(url, mapOf("Referer" to "https://rutube.ru/"))) ?: throw java.io.IOException("Rutube не отдал видео")
            q to m3u8
        } else q to url
    }

    /** The release with its episodes and files; Kino.pub's links are signed, so it is asked again each time. */
    suspend fun release(source: String, id: String): NativeRelease = when (source) {
        Anilibria.ID -> releases.getOrPut("$source:$id") { Anilibria.parseRelease(web.get(Anilibria.releaseUrl(id))) }
        KinoPub.ID -> {
            val token = kinopub.token() ?: throw java.io.IOException("Войдите в Kino.pub в настройках: «Источники и инфо».")
            KinoPub.parseItem(web.get(KinoPub.itemUrl(id, token)))
        }
        Filmix.ID -> filmix.call { api -> Filmix.parseItem(web.get(Filmix.itemUrl(api, id, filmix.devId, filmix.token)), NativeVoice.voice(id)) }
        Seasonvar.ID -> releases.getOrPut("$source:$id") { seasonvar(id) }
        Rutube.ID -> releases.getOrPut("$source:$id") { rutubeRelease(id) }
        else -> throw IllegalArgumentException(source)
    }

    /** Every season of a Seasonvar series in one voice-over; a season without it keeps the site's own. */
    private suspend fun seasonvar(id: String): NativeRelease {
        val first = NativeVoice.base(id)
        val voice = NativeVoice.voice(id)
        val firstHtml = web.get("${Seasonvar.HOST}/$first")
        val pages = Seasonvar.parseSeasons(firstHtml, first)
        val voices = mutableListOf<String>()
        val episodes = mutableListOf<NativeEpisode>()
        var poster: String? = null
        for (page in pages) {
            val html = if (page == first) firstHtml else runCatching { web.get("${Seasonvar.HOST}/$page") }.getOrNull() ?: continue
            val p = Seasonvar.parseSeasonPage(html) ?: continue
            poster = poster ?: p.poster
            p.voices.forEach { (_, name) -> if (name !in voices) voices += name }
            val translate = p.voices.firstOrNull { it.second == voice }?.first ?: ""
            val eps = runCatching { Seasonvar.parsePlaylist(web.get(Seasonvar.playlistUrl(p, translate), mapOf("Referer" to "${Seasonvar.HOST}/$page", "X-Requested-With" to "XMLHttpRequest")), p.season) }.getOrDefault(emptyList())
            episodes += eps.filter { e -> episodes.none { it.season == e.season && it.episode == e.episode } }
        }
        val name = Regex("<title>(.*?)</title>").find(firstHtml)?.groupValues?.get(1)
            ?.removePrefix("Сериал ")?.substringBefore(" онлайн")?.replace(Regex(" *[0-9]+ *сезон.*"), "")?.substringBefore("/")?.trim()
        return NativeRelease(Seasonvar.ID, id, name ?: first, null, null, true, poster, episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode })), voices = voices)
    }

    private suspend fun rutubeRelease(id: String): NativeRelease {
        val key = id.drop(1)
        if (id.startsWith("v")) return NativeRelease(Rutube.ID, id, "Rutube", null, null, false, null, episodes = listOf(NativeEpisode(0, 0, "Rutube", listOf("Авто" to Rutube.optionsUrl(key)))))
        val name = java.net.URLDecoder.decode(key, "UTF-8")
        val videos = (1..3).flatMap { page -> runCatching { Rutube.parseSearch(web.get(Rutube.searchUrl(name, page))) }.getOrDefault(emptyList()) }
        return NativeRelease(Rutube.ID, id, name, null, null, true, videos.firstOrNull()?.thumb, episodes = Rutube.episodes(videos, listOf(name)))
    }

    companion object {
        val ids = setOf(Anilibria.ID, KinoPub.ID, Filmix.ID, Seasonvar.ID, Rutube.ID)
        fun name(id: String) = when (id) {
            Anilibria.ID -> Anilibria.NAME
            KinoPub.ID -> KinoPub.NAME
            Filmix.ID -> Filmix.NAME
            Seasonvar.ID -> Seasonvar.NAME
            Rutube.ID -> Rutube.NAME
            else -> null
        }
    }
}
