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

data class Prefs(
    val theme: ThemeMode = ThemeMode.DARK,
    val uiMode: UiMode = UiMode.AUTO,
    val sort: SortOrder = SortOrder.RECENTLY_WATCHED,
)

private val Context.store by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val uiMode = stringPreferencesKey("ui_mode")
    private val sort = stringPreferencesKey("sort")

    val prefs: Flow<Prefs> = context.store.data.map { p ->
        Prefs(
            theme = enumOr(p[theme], ThemeMode.DARK),
            uiMode = enumOr(p[uiMode], UiMode.AUTO),
            sort = enumOr(p[sort], SortOrder.RECENTLY_WATCHED),
        )
    }

    suspend fun setTheme(value: ThemeMode) = context.store.edit { it[theme] = value.name }
    suspend fun setUiMode(value: UiMode) = context.store.edit { it[uiMode] = value.name }
    suspend fun setSort(value: SortOrder) = context.store.edit { it[sort] = value.name }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
