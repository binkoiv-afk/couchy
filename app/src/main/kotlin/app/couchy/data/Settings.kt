package app.couchy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.couchy.core.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе") }

/** How the interface is laid out: picked from the device, or forced by the user. */
enum class UiMode(val label: String) { AUTO("Автоматически"), TV("Для пульта"), TOUCH("Для сенсора") }

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

    val prefs: Flow<Prefs> = context.store.data.map { p ->
        Prefs(
            theme = enumOr(p[theme], ThemeMode.DARK),
            uiMode = enumOr(p[uiMode], UiMode.AUTO),
            sort = enumOr(p[sort], SortOrder.RECENTLY_WATCHED),
            torrEngine = enumOr(p[torrEngine], TorrEngineMode.BUILT_IN),
            torrAddress = p[torrAddress].orEmpty(),
            rezkaHost = p[rezkaHost].orEmpty(),
            searchHistory = p[searchHistory]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
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

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
