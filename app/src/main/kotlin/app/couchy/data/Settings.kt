package app.couchy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import app.couchy.core.SortOrder
import app.couchy.core.bypass.BypassKind
import app.couchy.core.bypass.BypassMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе") }

/** What the big banner on Home shows: new and popular titles, the person's own, or both in turn. */
enum class Banner(val label: String) { NEW("Новинки"), MINE("Моё любимое"), MIXED("Вперемешку") }

/** The accent colour of buttons, progress and marks: bright for dark screens, light, and deep for light screens. */
enum class Accent(val label: String, val main: Long, val light: Long, val deep: Long) {
    POPCORN("Попкорн", 0xFFFF8A3D, 0xFFFFB070, 0xFFEA6A1A),
    CHERRY("Вишня", 0xFFFF4D6D, 0xFFFF8FA3, 0xFFD81E45),
    SKY("Небо", 0xFF4DA3FF, 0xFF8CC4FF, 0xFF1F74D6),
    MINT("Мята", 0xFF34D399, 0xFF7EE7BF, 0xFF0F8F63),
    LAVENDER("Лаванда", 0xFFA78BFA, 0xFFC9B8FF, 0xFF7651DE),
    GOLD("Золото", 0xFFF5C518, 0xFFFFE07A, 0xFFB98C00),
}

/** How the interface is laid out: picked from the device, or forced by the user. */
enum class UiMode(val label: String) { AUTO("Авто"), TV("Пульт"), TOUCH("Сенсор") }

/** Which TorrServer plays torrents: the one inside Couchy, or one running elsewhere in the home network. */
enum class TorrEngineMode(val label: String) { BUILT_IN("Встроенный"), REMOTE("TorrServe в сети") }

data class Prefs(
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: Accent = Accent.POPCORN,
    val banner: Banner = Banner.NEW,
    val uiMode: UiMode = UiMode.AUTO,
    val sort: SortOrder = SortOrder.RECENTLY_WATCHED,
    val torrEngine: TorrEngineMode = TorrEngineMode.BUILT_IN,
    /** Address of an external TorrServe, like 192.168.1.20:8090. */
    val torrAddress: String = "",
    /** HDrezka mirror; empty means the default address. */
    val rezkaHost: String = "",
    /** The person's own TMDB key: film data in Russian. Empty: Cinemeta, in English. */
    val tmdbKey: String = "",
    /** The person's own kinopoiskapiunofficial.tech key: Kinopoisk rating and description. */
    val kpKey: String = "",
    val searchHistory: List<String> = emptyList(),
    val bypassMode: BypassMode = BypassMode.AUTO,
    val bypassKind: BypassKind = BypassKind.WARP,
    /** The person's own WireGuard / AmneziaWG config text. */
    val bypassConfig: String = "",
    /** The registered WARP device, as JSON; empty until first needed. */
    val warpAccount: String = "",
    /** Sites Couchy found blocked and now opens through the bypass. */
    val bypassHosts: Set<String> = emptySet(),
    /** WebDAV account for sync; empty url means sync is off. */
    val syncUrl: String = "",
    val syncLogin: String = "",
    val syncPassword: String = "",
    /** Four digits that let you out of kids mode; empty until a parent sets it. */
    val kidsPin: String = "",
    val kidsMode: Boolean = false,
)

private val Context.store by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val accent = stringPreferencesKey("accent")
    private val banner = stringPreferencesKey("banner")
    private val uiMode = stringPreferencesKey("ui_mode")
    private val sort = stringPreferencesKey("sort")
    private val torrEngine = stringPreferencesKey("torr_engine")
    private val torrAddress = stringPreferencesKey("torr_address")
    private val rezkaHost = stringPreferencesKey("rezka_host")
    private val tmdbKey = stringPreferencesKey("tmdb_key")
    private val kpKey = stringPreferencesKey("kp_key")
    private val searchHistory = stringPreferencesKey("search_history")
    private val bypassMode = stringPreferencesKey("bypass_mode")
    private val bypassKind = stringPreferencesKey("bypass_kind")
    private val bypassConfig = stringPreferencesKey("bypass_config")
    private val warpAccount = stringPreferencesKey("warp_account")
    private val bypassHosts = stringPreferencesKey("bypass_hosts")
    private val syncUrl = stringPreferencesKey("sync_url")
    private val syncLogin = stringPreferencesKey("sync_login")
    private val syncPassword = stringPreferencesKey("sync_password")
    private val kidsPin = stringPreferencesKey("kids_pin")
    private val kidsMode = stringPreferencesKey("kids_mode")

    val prefs: Flow<Prefs> = context.store.data.map { p ->
        Prefs(
            theme = enumOr(p[theme], ThemeMode.DARK),
            accent = enumOr(p[accent], Accent.POPCORN),
            banner = enumOr(p[banner], Banner.NEW),
            uiMode = enumOr(p[uiMode], UiMode.AUTO),
            sort = enumOr(p[sort], SortOrder.RECENTLY_WATCHED),
            torrEngine = enumOr(p[torrEngine], TorrEngineMode.BUILT_IN),
            torrAddress = p[torrAddress].orEmpty(),
            rezkaHost = p[rezkaHost].orEmpty(),
            tmdbKey = p[tmdbKey].orEmpty(),
            kpKey = p[kpKey].orEmpty(),
            searchHistory = p[searchHistory]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            bypassMode = enumOr(p[bypassMode], BypassMode.AUTO),
            bypassKind = enumOr(p[bypassKind], BypassKind.WARP),
            bypassConfig = p[bypassConfig].orEmpty(),
            warpAccount = p[warpAccount].orEmpty(),
            bypassHosts = p[bypassHosts]?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty(),
            syncUrl = p[syncUrl].orEmpty(),
            syncLogin = p[syncLogin].orEmpty(),
            syncPassword = p[syncPassword].orEmpty(),
            kidsPin = p[kidsPin].orEmpty(),
            kidsMode = p[kidsMode] == "1",
        )
    }

    suspend fun setTheme(value: ThemeMode) = context.store.edit { it[theme] = value.name }
    suspend fun setAccent(value: Accent) = context.store.edit { it[accent] = value.name }
    suspend fun setBanner(value: Banner) = context.store.edit { it[banner] = value.name }
    suspend fun setUiMode(value: UiMode) = context.store.edit { it[uiMode] = value.name }
    suspend fun setSort(value: SortOrder) = context.store.edit { it[sort] = value.name }
    suspend fun setTorrEngine(mode: TorrEngineMode, address: String) = context.store.edit {
        it[torrEngine] = mode.name
        it[torrAddress] = address.trim()
    }

    suspend fun setRezkaHost(value: String) = context.store.edit { it[rezkaHost] = value.trim() }
    suspend fun setTmdbKey(value: String) = context.store.edit { it[tmdbKey] = value.trim() }
    suspend fun setKpKey(value: String) = context.store.edit { it[kpKey] = value.trim() }
    suspend fun setSearchHistory(value: List<String>) = context.store.edit { it[searchHistory] = value.joinToString("\n") }

    suspend fun setBypass(mode: BypassMode, kind: BypassKind) = context.store.edit {
        it[bypassMode] = mode.name
        it[bypassKind] = kind.name
    }

    suspend fun setBypassConfig(value: String) = context.store.edit { it[bypassConfig] = value.trim() }
    suspend fun setWarpAccount(value: String) = context.store.edit { it[warpAccount] = value }
    suspend fun addBypassHost(host: String) = context.store.edit {
        val now = it[bypassHosts]?.split('\n')?.filter { h -> h.isNotBlank() }.orEmpty()
        if (host !in now) it[bypassHosts] = (now + host).joinToString("\n")
    }

    suspend fun clearBypassHosts() = context.store.edit { it.remove(bypassHosts) }
    suspend fun setBypassHosts(hosts: Set<String>) = context.store.edit { it[bypassHosts] = hosts.sorted().joinToString("\n") }

    suspend fun setKidsPin(pin: String) = context.store.edit { it[kidsPin] = pin }
    suspend fun setKidsMode(on: Boolean) = context.store.edit { it[kidsMode] = if (on) "1" else "0" }

    suspend fun setSyncAccount(url: String, login: String, password: String) = context.store.edit {
        it[syncUrl] = url.trim()
        it[syncLogin] = login.trim()
        it[syncPassword] = password
    }

    /** What moves to a new TV with the phone's QR: sign-ins, bypass and sources, not this device's look. */
    private val transferable get() = listOf(rezkaHost, tmdbKey, kpKey, bypassMode, bypassKind, bypassConfig, warpAccount, bypassHosts, syncUrl, syncLogin, syncPassword, kidsPin, torrEngine, torrAddress)

    suspend fun exportTransfer(): Map<String, String> {
        val p = context.store.data.first()
        // Google Drive works through the account on this phone; the TV signs in on its own.
        val google = p[syncUrl] == app.couchy.sync.WebDav.GOOGLE_ACCOUNT
        return transferable.mapNotNull { k -> p[k]?.let { v -> k.name to v } }.toMap()
            .filterKeys { !(google && it.startsWith("sync_")) }
    }

    suspend fun importTransfer(values: Map<String, String>) = context.store.edit { e ->
        transferable.forEach { k -> values[k.name]?.let { e[k] = it } }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
