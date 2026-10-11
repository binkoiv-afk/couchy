package app.couchy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.couchy.core.SortOrder
import app.couchy.core.bypass.BypassKind
import app.couchy.core.bypass.BypassMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе") }

/** How the interface is laid out: picked from the device, or forced by the user. */
enum class UiMode(val label: String) { AUTO("Авто"), TV("Пульт"), TOUCH("Сенсор") }

/** Which TorrServer plays torrents: the one inside Couchy, or one running elsewhere in the home network. */
enum class TorrEngineMode(val label: String) { BUILT_IN("Встроенный"), REMOTE("TorrServe в сети") }

data class Prefs(
    val theme: ThemeMode = ThemeMode.DARK,
    val uiMode: UiMode = UiMode.AUTO,
    val sort: SortOrder = SortOrder.RECENTLY_WATCHED,
    val torrEngine: TorrEngineMode = TorrEngineMode.BUILT_IN,
    /** Address of an external TorrServe, like 192.168.1.20:8090. */
    val torrAddress: String = "",
    /** HDrezka mirror; empty means the default address. */
    val rezkaHost: String = "",
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
    private val uiMode = stringPreferencesKey("ui_mode")
    private val sort = stringPreferencesKey("sort")
    private val torrEngine = stringPreferencesKey("torr_engine")
    private val torrAddress = stringPreferencesKey("torr_address")
    private val rezkaHost = stringPreferencesKey("rezka_host")
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
            uiMode = enumOr(p[uiMode], UiMode.AUTO),
            sort = enumOr(p[sort], SortOrder.RECENTLY_WATCHED),
            torrEngine = enumOr(p[torrEngine], TorrEngineMode.BUILT_IN),
            torrAddress = p[torrAddress].orEmpty(),
            rezkaHost = p[rezkaHost].orEmpty(),
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
    suspend fun setUiMode(value: UiMode) = context.store.edit { it[uiMode] = value.name }
    suspend fun setSort(value: SortOrder) = context.store.edit { it[sort] = value.name }
    suspend fun setTorrEngine(mode: TorrEngineMode, address: String) = context.store.edit {
        it[torrEngine] = mode.name
        it[torrAddress] = address.trim()
    }

    suspend fun setRezkaHost(value: String) = context.store.edit { it[rezkaHost] = value.trim() }
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

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
