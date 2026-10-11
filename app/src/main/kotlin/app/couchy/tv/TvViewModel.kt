package app.couchy.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.couchy.CouchyApp
import app.couchy.core.Kids
import app.couchy.LiveChannel
import app.couchy.Playback
import app.couchy.core.iptv.CatchupUrl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A channel with what is on it now and next. */
data class ChannelNow(val channel: ChannelEntity, val now: ProgramEntity?, val next: ProgramEntity?, val number: Int)

const val GROUP_FAVORITES = "★ Избранное"
const val GROUP_ALL = "Все каналы"

data class TvState(
    val playlists: List<PlaylistEntity> = emptyList(),
    val groups: List<String> = emptyList(),
    val group: String = GROUP_ALL,
    val channels: List<ChannelNow> = emptyList(),
    val favorites: List<ChannelNow> = emptyList(),
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TvViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as CouchyApp).tv

    /** Ticks every 30 seconds so "now" and progress lines move. */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private val group = MutableStateFlow(GROUP_ALL)
    private val refreshing = MutableStateFlow(false)

    private val upcoming = clock.flatMapLatest { now -> repo.upcoming(now - TvRepository.HOUR) }

    /** Kids mode shows only children's groups. */
    private val visibleChannels = combine(repo.channels, (app as CouchyApp).settings.prefs) { channels, p ->
        if (p.kidsMode) channels.filter { Kids.isKidsGroup(it.groupName) } else channels
    }

    val state: StateFlow<TvState> = combine(repo.playlists, visibleChannels, upcoming, group, combine(refreshing, clock) { r, c -> r to c }) { playlists, channels, programs, selected, (busy, now) ->
        val byEpg = programs.groupBy { it.epgId }
        val numbered = channels.mapIndexed { i, c ->
            val list = c.epgId?.let { byEpg[it] }.orEmpty()
            val current = list.firstOrNull { it.start <= now && it.stop > now }
            val next = list.firstOrNull { it.start >= (current?.stop ?: now) }
            ChannelNow(c, current, next, i + 1)
        }
        val groups = buildList {
            if (channels.any { it.favorite }) add(GROUP_FAVORITES)
            add(GROUP_ALL)
            addAll(channels.mapNotNull { it.groupName }.distinct())
        }
        val active = selected.takeIf { it in groups } ?: GROUP_ALL
        TvState(
            playlists = playlists,
            groups = groups,
            group = active,
            channels = filter(numbered, active),
            favorites = numbered.filter { it.channel.favorite },
            refreshing = busy,
            loaded = true,
            nowMs = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvState())

    private val focused = MutableStateFlow<ChannelEntity?>(null)

    /** The guide for the channel under focus (or picked on a phone): yesterday to tomorrow. */
    val schedule: StateFlow<List<ProgramEntity>> = focused.flatMapLatest { c ->
        val epg = c?.epgId ?: return@flatMapLatest flowOf(emptyList())
        val now = System.currentTimeMillis()
        val back = (c.catchupDays.coerceAtMost(7)).coerceAtLeast(1) * TvRepository.DAY
        repo.schedule(epg, now - back, now + TvRepository.DAY)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val focusedChannel: StateFlow<ChannelEntity?> = focused

    init {
        viewModelScope.launch { repo.refreshStale() }
    }

    fun selectGroup(name: String) { group.value = name }

    fun focus(channel: ChannelEntity?) { focused.value = channel }

    fun toggleFavorite(channel: ChannelEntity) = viewModelScope.launch { repo.setFavorite(channel, !channel.favorite) }

    fun addM3u(name: String, url: String, epg: String?) = busy { report(repo.addM3u(name, url, epg)) }

    fun addXtream(name: String, server: String, user: String, password: String) = busy { report(repo.addXtream(name, server, user, password)) }

    private val _problem = MutableStateFlow<String?>(null)
    /** Why a playlist just added did not load, for the person to see right away. */
    val problem: StateFlow<String?> = _problem

    fun problemShown() { _problem.value = null }

    private suspend fun report(id: Long) {
        val error = repo.allPlaylists().firstOrNull { it.id == id }?.lastError ?: return
        _problem.value = "Плейлист добавлен, но каналы не загрузились: $error. Проверьте ссылку или откройте её в браузере."
    }

    fun refreshAll() = busy { state.value.playlists.forEach { repo.refresh(it.id) } }

    fun delete(playlist: PlaylistEntity) = viewModelScope.launch { repo.delete(playlist.id) }

    private fun busy(block: suspend () -> Unit) = viewModelScope.launch {
        refreshing.value = true
        try { block() } finally { refreshing.value = false }
    }

    /** Live playback of a channel; zapping moves through [inGroup]. */
    fun live(channel: ChannelEntity, inGroup: List<ChannelEntity>): Playback =
        Playback(
            uri = channel.url,
            title = channel.name,
            startMs = 0,
            headers = channel.headers,
            live = LiveChannel(channel.id, channel.epgId, inGroup.map { it.id }),
        )

    /** A recorded programme from the channel's archive, or null when the archive does not reach it. */
    fun archive(channel: ChannelEntity, program: ProgramEntity): Playback? {
        val catchup = channel.catchup ?: return null
        val url = CatchupUrl.build(channel.url, catchup, program.start, program.stop, System.currentTimeMillis()) ?: return null
        return Playback(
            uri = url,
            title = "${channel.name} · ${program.title}",
            startMs = 0,
            headers = channel.headers,
            live = LiveChannel(channel.id, channel.epgId, emptyList(), archiveOf = program.start),
        )
    }

    /** The neighbour [delta] steps away in the zapping list, wrapping around. */
    suspend fun zap(live: LiveChannel, delta: Int): Playback? {
        val order = live.zapOrder.ifEmpty { return null }
        val index = order.indexOf(live.channelId).takeIf { it >= 0 } ?: return null
        val nextId = order[Math.floorMod(index + delta, order.size)]
        val channel = repo.channel(nextId) ?: return null
        return live(channel, emptyList()).copy(live = LiveChannel(channel.id, channel.epgId, order))
    }

    /** Another channel by its id, keeping the zapping list; for "previous channel". */
    suspend fun jump(live: LiveChannel, channelId: Long): Playback? {
        val channel = repo.channel(channelId) ?: return null
        return live(channel, emptyList()).copy(live = LiveChannel(channel.id, channel.epgId, live.zapOrder))
    }

    /** The channel with this number in the zapping list, as typed on the remote. */
    suspend fun byNumber(live: LiveChannel, number: Int): Playback? {
        val id = live.zapOrder.getOrNull(number - 1) ?: return null
        return jump(live, id)
    }

    fun toggleFavorite(channelId: Long) = viewModelScope.launch {
        repo.channel(channelId)?.let { repo.setFavorite(it, !it.favorite) }
    }

    private fun filter(all: List<ChannelNow>, group: String) = when (group) {
        GROUP_ALL -> all
        GROUP_FAVORITES -> all.filter { it.channel.favorite }
        else -> all.filter { it.channel.groupName == group }
    }
}
