package app.couchy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.couchy.core.SortOrder
import app.couchy.data.MediaEntity
import app.couchy.data.Prefs
import app.couchy.data.ThemeMode
import app.couchy.ui.components.FormFactor
import app.couchy.ui.components.LocalFormFactor
import app.couchy.ui.home.HomeActions
import app.couchy.ui.home.HomeContent
import app.couchy.ui.library.LibraryActions
import app.couchy.ui.library.LibraryContent
import app.couchy.ui.player.PlayerControls
import app.couchy.ui.player.PlayerOverlay
import app.couchy.ui.player.LiveUi
import app.couchy.ui.player.PlayerUi
import app.couchy.tv.ChannelEntity
import app.couchy.tv.ChannelNow
import app.couchy.tv.GROUP_ALL
import app.couchy.tv.GROUP_FAVORITES
import app.couchy.tv.PlaylistEntity
import app.couchy.tv.ProgramEntity
import app.couchy.tv.TvState
import app.couchy.ui.tv.TvActions
import app.couchy.ui.tv.TvContent
import app.couchy.ui.settings.SettingsActions
import app.couchy.ui.settings.SettingsContent
import app.couchy.ui.settings.UpdateState
import app.couchy.ui.shell.Destination
import app.couchy.ui.shell.Shell
import app.couchy.ui.theme.CouchyTheme
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** Renders the main screens on the JVM; CI publishes the pictures so the design can be checked without a device. */
class ScreensTest {
    private val phone = DeviceConfig.PIXEL_6
    private val tablet = DeviceConfig.PIXEL_C
    private val tv = DeviceConfig.PIXEL_6.copy(
        screenWidth = 1920,
        screenHeight = 1080,
        xdpi = 320,
        ydpi = 320,
        density = Density.XHIGH,
        orientation = ScreenOrientation.LANDSCAPE,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = phone, maxPercentDifference = 100.0)

    private fun snap(name: String, device: DeviceConfig, form: FormFactor, theme: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(device)
        paparazzi.snapshot(name) {
            CouchyTheme(theme) {
                CompositionLocalProvider(LocalFormFactor provides form) { content() }
            }
        }
    }

    private val minute = 60_000L
    private val items = listOf(
        media("Орбита · S2E5", 12 * minute, 50 * minute, watchedAgo = 1, favorite = true),
        media("Лисья нора · S3E2", 34 * minute, 52 * minute, watchedAgo = 2),
        media("Северный ветер", 46 * minute, 128 * minute, watchedAgo = 3, favorite = true),
        media("Последний рейс · S1E6", 40 * minute, 49 * minute, watchedAgo = 4),
        media("Код Атлантиды", 0, 0, watchedAgo = 0, uri = "https://cdn.example/atlantis/master.m3u8"),
        media("Неон", 101 * minute, 104 * minute, watchedAgo = 6),
        media("Маленький дракон", 0, 0, watchedAgo = 0, uri = "content://media/dragon.mkv", favorite = true),
    )

    private fun media(
        title: String, position: Long, duration: Long, watchedAgo: Int, favorite: Boolean = false,
        uri: String = "https://example.com/${title.hashCode()}.mp4",
    ) = MediaEntity(
        uri = uri,
        title = title,
        kind = if (uri.endsWith("m3u8")) "HLS" else "VIDEO",
        addedAt = 1_000L * (100 - watchedAgo),
        lastWatchedAt = if (watchedAgo == 0) 0 else 10_000L - watchedAgo,
        positionMs = position,
        durationMs = duration,
        favorite = favorite,
    )

    private fun homeState() = HomeState(
        continueWatching = items.filter { it.positionMs > 0 && it.positionMs < it.durationMs * 0.92 },
        favorites = items.filter { it.favorite },
        recent = items.sortedByDescending { it.addedAt },
        loaded = true,
    )

    private val homeActions = HomeActions({}, {}, {}, {})
    private val libraryActions = LibraryActions({}, {}, {}, {})

    @Composable
    private fun home(state: HomeState = homeState()) =
        Shell(Destination.HOME, {}, {}) { HomeContent(state, homeActions) }

    @Composable
    private fun library() = Shell(Destination.LIBRARY, {}, {}) {
        LibraryContent(LibraryState(items, LibraryFilter.ALL, SortOrder.RECENTLY_WATCHED, items.size), libraryActions)
    }

    @Test fun phoneHome() = snap("phone_home", phone, FormFactor.PHONE) { home() }
    @Test fun phoneHomeLight() = snap("phone_home_light", phone, FormFactor.PHONE, ThemeMode.LIGHT) { home() }
    @Test fun phoneWelcome() = snap("phone_welcome", phone, FormFactor.PHONE) { home(HomeState(loaded = true)) }
    @Test fun phoneLibrary() = snap("phone_library", phone, FormFactor.PHONE) { library() }
    @Test fun phoneSettings() = snap("phone_settings", phone, FormFactor.PHONE) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(Prefs(), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}))
        }
    }
    @Test fun tvHome() = snap("tv_home", tv, FormFactor.TV) { home() }
    @Test fun tvHomeLight() = snap("tv_home_light", tv, FormFactor.TV, ThemeMode.LIGHT) { home() }
    @Test fun tvWelcome() = snap("tv_welcome", tv, FormFactor.TV) { home(HomeState(loaded = true)) }
    @Test fun tvLibrary() = snap("tv_library", tv, FormFactor.TV) { library() }
    @Test fun tabletHome() = snap("tablet_home", tablet, FormFactor.TABLET) { home() }

    // 20:40 local-ish fixed time so the guide looks the same on every run.
    private val now = 1_760_000_000_000L - 1_760_000_000_000L % (30 * minute) + 10 * minute
    private val channelNames = listOf(
        "Первый" to "Общие", "Россия 1" to "Общие", "Матч ТВ" to "Спорт", "НТВ" to "Общие", "Пятница!" to "Развлечения",
        "Discovery" to "Познавательные", "Кинохит" to "Кино", "Мульт" to "Детские", "Euronews" to "Новости", "Матч! Футбол 1" to "Спорт",
    )
    private val shows = listOf("Вечерние новости", "Время покажет", "Футбол. Лига чемпионов", "Следствие вели", "Орёл и решка",
        "Как это устроено", "Ледокол «Ноябрь»", "Фиксики", "Мир сегодня", "Обзор тура")
    private val tvChannels = channelNames.mapIndexed { i, (name, group) ->
        val channel = ChannelEntity(
            id = i + 1L, playlistId = 1, position = i, name = name, groupName = group, logo = null,
            url = "http://iptv.example/$i.m3u8", tvgId = "c$i", tvgName = null, epgId = "c$i",
            catchupType = if (i % 3 == 2) null else "SHIFT", catchupSource = null, catchupDays = 3,
            userAgent = null, referrer = null, favorite = i in setOf(0, 2, 6),
        )
        val start = now - (10 + i * 7) * minute
        val current = ProgramEntity(i * 10L, "c$i", start, start + (40 + i * 9) * minute, shows[i], null)
        val next = ProgramEntity(i * 10L + 1, "c$i", current.stop, current.stop + 50 * minute, shows[(i + 3) % shows.size], null)
        ChannelNow(channel, current, next, i + 1)
    }
    private val tvState = TvState(
        playlists = listOf(PlaylistEntity(id = 1, name = "Домашний", kind = "M3U", url = "http://iptv.example/list.m3u", channelCount = 10)),
        groups = listOf(GROUP_FAVORITES, GROUP_ALL, "Общие", "Спорт", "Кино", "Детские", "Новости", "Познавательные", "Развлечения"),
        group = GROUP_ALL,
        channels = tvChannels,
        favorites = tvChannels.filter { it.channel.favorite },
        loaded = true,
        nowMs = now,
    )
    private val tvSchedule = (-4..5).map { k ->
        val start = now - 10 * minute + k * 45 * minute
        ProgramEntity(100L + k, "c0", start, start + 45 * minute, listOf("Новости", "Доброе утро", "Жить здорово!", "Модный приговор", "Время покажет", "Вечерние новости", "Поле чудес", "Голос", "Вечерний Ургант", "Познер")[k + 4], null)
    }
    private val tvActions = TvActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {})

    @Test fun tvTv() = snap("tv_tv", tv, FormFactor.TV) {
        Shell(Destination.TV, {}, {}) { TvContent(tvState, tvChannels[0].channel, tvSchedule, tvActions) }
    }
    @Test fun phoneTv() = snap("phone_tv", phone, FormFactor.PHONE) {
        Shell(Destination.TV, {}, {}) { TvContent(tvState, null, emptyList(), tvActions) }
    }
    @Test fun tabletTv() = snap("tablet_tv", tablet, FormFactor.TABLET) {
        Shell(Destination.TV, {}, {}) { TvContent(tvState, tvChannels[2].channel, tvSchedule, tvActions) }
    }
    @Test fun phoneTvEmpty() = snap("phone_tv_empty", phone, FormFactor.PHONE) {
        Shell(Destination.TV, {}, {}) { TvContent(TvState(loaded = true), null, emptyList(), tvActions) }
    }
    @Test fun tvPlayerLive() = snap("tv_player_live", tv, FormFactor.TV) {
        val c = tvChannels[2]
        player(touch = false, live = LiveUi(c.channel.name, c.number, c.now?.title, c.now!!.start, c.now!!.stop, c.next?.title, c.next!!.start, now))
    }

    @Test fun tvPlayer() = snap("tv_player", tv, FormFactor.TV) { player(touch = false) }
    @Test fun phonePlayer() = snap(
        "phone_player",
        phone.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = phone.screenHeight, screenHeight = phone.screenWidth),
        FormFactor.PHONE,
    ) { player(touch = true) }

    @Composable
    private fun player(touch: Boolean, live: LiveUi? = null) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1D2B4F), Color(0xFF0A0F1F))))) {
            PlayerOverlay(
                PlayerUi(
                    title = "Ледокол «Ноябрь»",
                    positionMs = 51 * minute + 22_000,
                    durationMs = 135 * minute,
                    playing = true,
                    buffering = false,
                    qualityLabel = "Авто · 1080p",
                    live = live,
                ),
                visible = true,
                controls = PlayerControls({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                touch = touch,
            )
        }
    }
}
