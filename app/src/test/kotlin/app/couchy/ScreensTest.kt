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
import app.couchy.core.sources.EpisodeRef
import app.couchy.core.sources.RezkaPage
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleKind
import app.couchy.core.sources.TorrentHit
import app.couchy.core.sources.TorrentHits
import app.couchy.core.sources.Translator
import app.couchy.sources.RezkaUi
import app.couchy.sources.SearchState
import app.couchy.sources.SourceStatus
import app.couchy.sources.TitleUi
import app.couchy.ui.search.SearchActions
import app.couchy.ui.search.SearchContent
import app.couchy.ui.search.TitleActions
import app.couchy.ui.search.TitleContent
import app.couchy.core.torrent.TorrFile
import app.couchy.torrent.TorrentFileUi
import app.couchy.torrent.TorrentPhase
import app.couchy.torrent.TorrentState
import app.couchy.ui.torrent.TorrentActions
import app.couchy.ui.torrent.TorrentContent
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
    private val feed by lazy {
        app.couchy.sources.Feed(
            fresh = found.take(3).map { it.copy(info = "${it.year ?: 2024}, США, фантастика") },
            films = found.drop(1),
            series = found.filter { it.kind == TitleKind.SERIES } + found.take(2),
            similar = found.drop(3),
            similarTo = "Интерстеллар",
            loaded = true,
        )
    }
    private val libraryActions = LibraryActions({}, {}, {}, {})

    @Composable
    private fun home(state: HomeState = homeState()) =
        Shell(Destination.HOME, {}, {}) { HomeContent(state, homeActions, feed = feed) }

    @Composable
    private fun library() = Shell(Destination.LIBRARY, {}, {}) {
        LibraryContent(LibraryState(items, LibraryFilter.ALL, SortOrder.RECENTLY_WATCHED, items.size), libraryActions)
    }

    @Test fun phoneHome() = snap("phone_home", phone, FormFactor.PHONE) { home() }
    @Test fun phoneHomeLight() = snap("phone_home_light", phone, FormFactor.PHONE, ThemeMode.LIGHT) { home() }
    @Test fun phoneWelcome() = snap("phone_welcome", phone, FormFactor.PHONE) {
        Shell(Destination.HOME, {}, {}) { HomeContent(HomeState(loaded = true), homeActions, feed = app.couchy.sources.Feed(loaded = true)) }
    }
    @Test fun phoneLibrary() = snap("phone_library", phone, FormFactor.PHONE) { library() }
    @Test fun phoneSettings() = snap("phone_settings", phone, FormFactor.PHONE) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(Prefs(), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}))
        }
    }
    @Test fun phoneSettingsBypass() = snap("phone_settings_bypass", phone, FormFactor.PHONE) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(
                Prefs(bypassHosts = setOf("rezka.ag", "kinozal.tv")), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}),
                bypass = app.couchy.ui.settings.BypassUi("Подключён: WARP"),
                castInfo = "Это устройство: Xiaomi Mi Box S, адрес 192.168.1.20",
                sync = app.couchy.ui.settings.SyncUi(true, "https://webdav.yandex.ru", "igor@yandex.ru", "Синхронизировано в 10:40"),
                initialPage = app.couchy.ui.settings.SettingsPage.BYPASS,
            )
        }
    }
    @Test fun phoneKids() = snap("phone_kids", phone, FormFactor.PHONE) {
        androidx.compose.runtime.CompositionLocalProvider(app.couchy.ui.shell.LocalKidsMode provides true) { home() }
    }
    @Test fun phoneSettingsLook() = snap("phone_settings_look", phone, FormFactor.PHONE) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(Prefs(), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}), initialPage = app.couchy.ui.settings.SettingsPage.APPEARANCE)
        }
    }
    @Test fun tvSettings() = snap("tv_settings", tv, FormFactor.TV) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(Prefs(), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}), castInfo = "Это устройство: Mi Box S, адрес 192.168.1.20")
        }
    }
    @Test fun phoneSearchEmpty() = snap("phone_search_empty", phone, FormFactor.PHONE) {
        Shell(Destination.SEARCH, {}, {}) { SearchContent(SearchState(), searchActions) }
    }
    @Test fun phoneSettingsLight() = snap("phone_settings_light", phone, FormFactor.PHONE, ThemeMode.LIGHT) {
        Shell(Destination.SETTINGS, {}, {}) {
            SettingsContent(Prefs(), "0.1.1", UpdateState.Idle, SettingsActions({}, {}, {}, {}), initialPage = app.couchy.ui.settings.SettingsPage.APPEARANCE)
        }
    }
    @Test fun tvKids() = snap("tv_kids", tv, FormFactor.TV) {
        androidx.compose.runtime.CompositionLocalProvider(app.couchy.ui.shell.LocalKidsMode provides true) { home() }
    }
    @Test fun tvHome() = snap("tv_home", tv, FormFactor.TV) { home() }
    @Test fun tvHomeLight() = snap("tv_home_light", tv, FormFactor.TV, ThemeMode.LIGHT) { home() }
    @Test fun tvWelcome() = snap("tv_welcome", tv, FormFactor.TV) {
        Shell(Destination.HOME, {}, {}) { HomeContent(HomeState(loaded = true), homeActions, feed = app.couchy.sources.Feed(loaded = true)) }
    }
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

    private val ytVideos = listOf(
        app.couchy.core.youtube.YtVideo("aaaaaaaaaa1", "Как устроен ледокол: экскурсия по машинному отделению", "Наука в деталях", durationSec = 1342, views = 1_240_000, ago = "3 дня назад", thumb = ""),
        app.couchy.core.youtube.YtVideo("aaaaaaaaaa2", "Обзор новинок кино за октябрь", "КиноЛаб", durationSec = 905, views = 86_000, ago = "вчера", thumb = ""),
        app.couchy.core.youtube.YtVideo("aaaaaaaaaa3", "Футбол: лучшие моменты тура", "Матч Хайлайтс", live = true, views = 12_400, thumb = ""),
        app.couchy.core.youtube.YtVideo("aaaaaaaaaa4", "Готовим хинкали дома", "Кухня Тбилиси", durationSec = 611, views = 430_000, ago = "2 недели назад", thumb = ""),
    )
    private val ytState = app.couchy.youtube.YouTubeUi(
        shelf = app.couchy.youtube.YtShelf.Subscriptions,
        kiosks = listOf(app.couchy.youtube.YtShelf.Kiosk("Trending", "В тренде"), app.couchy.youtube.YtShelf.Kiosk("trending_music", "Музыка")),
        list = app.couchy.youtube.YtListUi(ytVideos, end = true),
        subscriptions = listOf(
            app.couchy.core.youtube.YtChannel("https://www.youtube.com/channel/UC1", "Наука в деталях"),
            app.couchy.core.youtube.YtChannel("https://www.youtube.com/channel/UC2", "КиноЛаб"),
            app.couchy.core.youtube.YtChannel("https://www.youtube.com/channel/UC3", "Кухня Тбилиси"),
        ),
        history = listOf("ледокол", "лучшие голы"),
    )
    private val ytActions = app.couchy.ui.youtube.YouTubeActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})

    @Test fun phoneYoutube() = snap("phone_youtube", phone, FormFactor.PHONE) {
        Shell(Destination.YOUTUBE, {}, {}) { app.couchy.ui.youtube.YouTubeContent(ytState, ytActions) }
    }
    @Test fun tvYoutube() = snap("tv_youtube", tv, FormFactor.TV) {
        Shell(Destination.YOUTUBE, {}, {}) { app.couchy.ui.youtube.YouTubeContent(ytState, ytActions) }
    }
    @Test fun phoneYoutubeChannel() = snap("phone_youtube_channel", phone, FormFactor.PHONE) {
        Shell(Destination.YOUTUBE, {}, {}) {
            app.couchy.ui.youtube.YouTubeContent(
                ytState.copy(channel = app.couchy.youtube.YtChannelUi(app.couchy.core.youtube.YtChannel("https://www.youtube.com/channel/UC1", "Наука в деталях", subscribers = 1_830_000), app.couchy.youtube.YtListUi(ytVideos.take(2), end = true))),
                ytActions,
            )
        }
    }

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

    private val torrentFiles = (1..8).map { n ->
        val file = TorrFile(n, "Orbita.S01E0$n.1080p.WEB-DL.mkv", 1_900_000_000L + n * 37_000_000L)
        TorrentFileUi(
            file = file,
            label = "S1 · Серия $n",
            key = "t#$n",
            progress = when (n) { 3 -> 0.42f; else -> 0f },
            watched = n < 3,
            lastWatchedAt = if (n <= 3) n.toLong() else 0,
        )
    }
    private val torrentState = TorrentState(
        link = "magnet:?xt=urn:btih:abc",
        title = "Орбита / Orbita. Сезон 1 (2025) WEB-DL 1080p",
        phase = TorrentPhase.READY,
        size = torrentFiles.sumOf { it.file.length },
        files = torrentFiles,
        resume = torrentFiles[2],
        peers = 48,
        activePeers = 17,
        seeders = 12,
        speed = 6.3 * 1024 * 1024,
    )
    private val torrentActions = TorrentActions({}, {}, {}, {})

    @Test fun tvTorrent() = snap("tv_torrent", tv, FormFactor.TV) { TorrentContent(torrentState, torrentActions) }
    @Test fun phoneTorrent() = snap("phone_torrent", phone, FormFactor.PHONE) { TorrentContent(torrentState, torrentActions) }
    @Test fun phoneTorrentWaiting() = snap("phone_torrent_wait", phone, FormFactor.PHONE) {
        TorrentContent(TorrentState(link = "magnet:?", title = "Северный ветер (2024) 2160p", phase = TorrentPhase.METADATA, peers = 3), torrentActions)
    }
    @Test fun tvPlayerTorrent() = snap("tv_player_torrent", tv, FormFactor.TV) { player(touch = false, status = "17 из 48 пиров · 6,3 МБ/с") }

    private val found = listOf(
        Title("Дюна: Часть вторая", "Dune: Part Two", year = 2024, rating = "КП 8.17", imdbId = "tt15239678"),
        Title("Дюна", "Dune: Part One", year = 2021, rating = "КП 7.68"),
        Title("Дюна: Пророчество", "Dune: Prophecy", year = 2024, kind = TitleKind.SERIES, rating = "КП 7.16"),
        Title("«Дюна» Ходоровского", "Jodorowsky's Dune", year = 2013, rating = "КП 8.08"),
        Title("Дюна: Часть третья", "Dune: Part Three", year = 2026),
        Title("Dune", "Dune", year = 1984, imdbId = "tt0087182", info = "Kyle MacLachlan, Virginia Madsen"),
        Title("Долгая дорога в дюнах", year = 1980, kind = TitleKind.SERIES),
        Title("Dune Drifter", year = 2020),
    )
    private val searchState = SearchState(query = "дюна", searched = true, titles = found)
    private val searchActions = SearchActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun hit(name: String, seeds: Int, gb: Double, tracker: String, voices: List<String> = emptyList()) = TorrentHit(
        title = name, magnet = "magnet:?xt=urn:btih:${name.hashCode()}", hash = name.hashCode().toString(), size = (gb * (1L shl 30)).toLong(),
        seeds = seeds, tracker = tracker, quality = TorrentHits.qualityOf(name), voices = voices, hdr = "HDR" in name,
    )
    private val torrentGroups = TorrentHits.group(
        listOf(
            hit("Дюна: Часть вторая / Dune: Part Two (2024) WEB-DL 2160p HDR | Дубляж", 412, 31.2, "rutracker", listOf("Дубляж")),
            hit("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX", 433, 29.3, "TorrentGalaxy"),
            hit("Дюна: Часть вторая / Dune: Part Two (2024) WEB-DL 1080p | Дубляж, MVO", 1798, 12.2, "rutor", listOf("Дубляж", "MVO")),
            hit("Dune Part Two 2024 1080p WEBRip", 1090, 2.76, "YTS"),
            hit("Дюна: Часть вторая (2024) WEB-DLRip 1080p | Jaskier", 132, 1.99, "bitru", listOf("Jaskier")),
            hit("Dune.Part.Two.2024.1080p.WEBRip.1600MB.DD5.1.x264-GalaxyRG", 299, 1.6, "TorrentGalaxy"),
            hit("Dune.Part.Two.2024.1080p.WEB-DL.H264.AAC-EVO", 154, 6.59, "1337x"),
            hit("Дюна: Часть вторая (2024) WEB-DLRip 720p | Дубляж", 640, 4.37, "kinozal", listOf("Дубляж")),
        ),
    )
    private val titleUi = TitleUi(
        title = found[2],
        description = "За десять тысяч лет до восхождения Пола Атрейдеса сёстры Харконнен основывают легендарный орден Бене Гессерит.",
        rezka = RezkaUi(
            SourceStatus.READY,
            url = "https://rezka.ag/series/fiction/74658-dyuna-prorochestvo-2024.html",
            page = RezkaPage(74658, true, "Дюна: Пророчество", "Dune: Prophecy", null, null, "IMDb 7.1",
                listOf(Translator(56, "Дубляж"), Translator(111, "HDrezka Studio"), Translator(238, "LostFilm"), Translator(1, "Оригинал · Английский")), 56,
                (1..6).map { EpisodeRef(1, it, "Серия $it") },
                app.couchy.core.sources.TitleDetails(
                    slogan = "Власть рождается в тени",
                    released = "18 ноября 2024 года",
                    countries = listOf(app.couchy.core.sources.Link("США", "https://rezka.ag/country/USA/")),
                    genres = listOf(app.couchy.core.sources.Link("Фантастика", "https://rezka.ag/series/fiction/"), app.couchy.core.sources.Link("Драмы", "https://rezka.ag/series/drama/")),
                    directors = listOf(app.couchy.core.sources.Link("Анна Фёрстер", "https://rezka.ag/person/1-anna/")),
                    actors = listOf("Эмили Уотсон", "Оливия Уильямс", "Трэвис Фиммел", "Джоди Мэй", "Марк Стронг", "Индира Варма").mapIndexed { i, n ->
                        app.couchy.core.sources.Link(n, "https://rezka.ag/person/${i + 10}-x/")
                    },
                    age = "18+",
                    duration = "60 мин.",
                    similar = found.take(2) + found.drop(4),
                    parts = found.take(2),
                )),
            translator = 56,
            episodes = (1..6).map { EpisodeRef(1, it, "Серия $it") } + (1..2).map { EpisodeRef(2, it, "Серия $it") },
            season = 1,
        ),
        torrents = torrentGroups,
        torrentsLoading = false,
        progress = mapOf("1:3" to 0.4f),
        watched = setOf("1:1", "1:2"),
        sites = listOf(
            app.couchy.core.sources.SiteHit("kinokrad", "Дюна: Пророчество", 2024, "https://kinokrad.cc/473232-dune-the-prophecy-2024.html", null),
            app.couchy.core.sources.SiteHit("seasonvar", "Дюна: Пророчество", null, "https://seasonvar.ru/serial-40000-Dyuna_Prorochestvo.html", null),
        ),
        sitesLoading = false,
    )
    private val titleActions = TitleActions({}, {}, {}, { _, _, _ -> }, {}, {}, {})

    @Test fun phoneSearch() = snap("phone_search", phone, FormFactor.PHONE) { Shell(Destination.SEARCH, {}, {}) { SearchContent(searchState, searchActions) } }
    @Test fun tvSearch() = snap("tv_search", tv, FormFactor.TV) { Shell(Destination.SEARCH, {}, {}) { SearchContent(searchState, searchActions) } }
    @Test fun phoneSearchHistory() = snap("phone_search_history", phone, FormFactor.PHONE) {
        Shell(Destination.SEARCH, {}, {}) {
            SearchContent(SearchState(history = listOf("дюна", "во все тяжкие", "оппенгеймер")), searchActions, shelves = listOf("Сейчас смотрят" to found, "Просмотрено" to found.reversed()))
        }
    }
    @Test fun phoneSearchTyping() = snap("phone_search_typing", phone, FormFactor.PHONE) {
        Shell(Destination.SEARCH, {}, {}) {
            SearchContent(SearchState(query = "дю", typing = true, hints = listOf("дюна 2"), suggestions = found), searchActions)
        }
    }
    private val tall = phone.copy(screenHeight = phone.screenHeight * 3)
    @Test fun phoneTitleFull() = snap("phone_title_full", tall, FormFactor.PHONE) { TitleContent(titleUi, titleActions) }
    private val listingActions = app.couchy.ui.search.ListingActions({}, {}, {}, {})
    @Test fun phoneCatalog() = snap("phone_catalog", phone, FormFactor.PHONE) {
        app.couchy.ui.search.ListingContent(
            app.couchy.sources.ListingUi(
                app.couchy.sources.ListQuery.Catalog(app.couchy.core.sources.CatalogFilter(genre = "fiction", year = 2024)),
                titles = found + found.map { it.copy(name = it.name + " 2", imdbId = null) },
                loading = false, page = 1,
            ),
            listingActions,
        )
    }
    @Test fun phonePerson() = snap("phone_person", phone, FormFactor.PHONE) {
        app.couchy.ui.search.ListingContent(
            app.couchy.sources.ListingUi(app.couchy.sources.ListQuery.Person("Тимоти Шаламе", "https://rezka.ag/person/1-x/"), titles = found, loading = false, end = true),
            listingActions,
        )
    }
    @Test fun tvCatalog() = snap("tv_catalog", tv, FormFactor.TV) {
        app.couchy.ui.search.ListingContent(
            app.couchy.sources.ListingUi(app.couchy.sources.ListQuery.Catalog(app.couchy.core.sources.CatalogFilter()), titles = found, loading = true),
            listingActions,
        )
    }
    @Test fun tvTitle() = snap("tv_title", tv, FormFactor.TV) { TitleContent(titleUi, titleActions) }
    @Test fun phoneTitle() = snap("phone_title", phone, FormFactor.PHONE) { TitleContent(titleUi, titleActions) }
    @Test fun phoneTitleBlocked() = snap("phone_title_blocked", phone, FormFactor.PHONE) {
        TitleContent(titleUi.copy(title = found[0], rezka = RezkaUi(SourceStatus.BLOCKED), sites = emptyList(), sitesLoading = true, torrentsLoading = true, torrents = emptyList()), titleActions)
    }

    @Test fun phoneTitleSite() = snap("phone_title_site", phone, FormFactor.PHONE) {
        TitleContent(titleUi.copy(rezka = RezkaUi(SourceStatus.NOT_FOUND)), titleActions)
    }

    @Test fun tvPlayer() = snap("tv_player", tv, FormFactor.TV) { player(touch = false) }
    @Test fun phonePlayer() = snap(
        "phone_player",
        phone.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = phone.screenHeight, screenHeight = phone.screenWidth),
        FormFactor.PHONE,
    ) { player(touch = true, full = true) }
    @Test fun phonePlayerUpright() = snap("phone_player_upright", phone, FormFactor.PHONE) { player(touch = true, full = true) }

    @Composable
    private fun player(touch: Boolean, live: LiveUi? = null, status: String? = null, full: Boolean = false) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1D2B4F), Color(0xFF0A0F1F))))) {
            PlayerOverlay(
                PlayerUi(
                    title = "Ледокол «Ноябрь»",
                    positionMs = 51 * minute + 22_000,
                    durationMs = 135 * minute,
                    playing = true,
                    qualityLabel = "Авто · 1080p",
                    live = live,
                    status = status,
                    buffering = status != null,
                ),
                visible = true,
                controls = if (full) {
                    PlayerControls({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onCast = {}, onRotate = {}, onIntro = {}, onDownload = {}, onPip = {})
                } else {
                    PlayerControls({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                },
                touch = touch,
            )
        }
    }
}
