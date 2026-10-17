package app.couchy.tv

import android.util.Xml
import app.couchy.core.iptv.Catchup
import app.couchy.core.iptv.Epg
import app.couchy.core.iptv.M3u
import app.couchy.core.iptv.M3uChannel
import app.couchy.core.iptv.XtreamAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

class TvRepository(private val dao: TvDao) {
    val playlists = dao.observePlaylists()
    val channels = dao.observeChannels()

    fun upcoming(now: Long) = dao.observeUpcoming(now, now + 12 * HOUR)

    fun schedule(epgId: String, from: Long, to: Long) = dao.observeSchedule(epgId, from, to)

    suspend fun channel(id: Long) = dao.channel(id)

    suspend fun programsBetween(from: Long, to: Long) = dao.programsBetween(from, to)

    suspend fun setFavorite(channel: ChannelEntity, favorite: Boolean) = dao.setFavorite(channel.id, favorite)

    suspend fun addM3u(name: String, url: String, epg: String?): Long {
        val id = dao.insertPlaylist(PlaylistEntity(name = name, kind = PlaylistKind.M3U.name, url = url.trim(), epgOverride = epg?.trim()?.ifEmpty { null }))
        refresh(id)
        return id
    }

    suspend fun addXtream(name: String, server: String, username: String, password: String): Long {
        val id = dao.insertPlaylist(
            PlaylistEntity(name = name, kind = PlaylistKind.XTREAM.name, url = server.trim(), username = username.trim(), password = password),
        )
        refresh(id)
        return id
    }

    suspend fun delete(id: Long) = dao.deletePlaylist(id)

    suspend fun allPlaylists(): List<PlaylistEntity> = dao.playlists()

    /** Refreshes every playlist whose channels or guide are older than [maxAgeMs]. */
    suspend fun refreshStale(maxAgeMs: Long = 12 * HOUR) {
        val now = System.currentTimeMillis()
        for (p in dao.playlists()) {
            if (now - p.updatedAt > maxAgeMs) refresh(p.id)
            else if (now - p.epgUpdatedAt > maxAgeMs) refreshEpg(p.id)
        }
    }

    /** Downloads the channel list again, keeping favorites, then the guide. Errors are stored on the playlist. */
    suspend fun refresh(id: Long) = withContext(Dispatchers.IO) {
        val playlist = dao.playlist(id) ?: return@withContext
        try {
            val favorites = dao.channels(id).filter { it.favorite }.map { it.name }.toSet()
            val (channels, epg) = when (PlaylistKind.valueOf(playlist.kind)) {
                PlaylistKind.M3U -> loadM3u(playlist)
                PlaylistKind.XTREAM -> loadXtream(playlist)
            }
            if (channels.isEmpty()) throw IOException("В плейлисте нет каналов")
            dao.replaceChannels(id, channels.map { it.copy(favorite = it.name in favorites) })
            dao.updatePlaylist(
                playlist.copy(
                    updatedAt = System.currentTimeMillis(),
                    channelCount = channels.size,
                    epgDetected = epg.joinToString(",").ifEmpty { null },
                    lastError = null,
                ),
            )
        } catch (e: Exception) {
            dao.updatePlaylist(playlist.copy(lastError = e.message ?: e.javaClass.simpleName))
            return@withContext
        }
        refreshEpg(id)
    }

    private fun loadM3u(p: PlaylistEntity): Pair<List<ChannelEntity>, List<String>> {
        val text = Http.text(p.url)
        if (!M3u.looksLikeM3u(text)) throw IOException("Это не плейлист M3U")
        val parsed = M3u.parse(text)
        return parsed.channels.mapIndexed { i, c -> c.toEntity(p.id, i) } to parsed.epgUrls
    }

    private fun M3uChannel.toEntity(playlistId: Long, position: Int) = ChannelEntity(
        playlistId = playlistId,
        position = position,
        name = name,
        groupName = group,
        logo = logo,
        url = url,
        tvgId = tvgId,
        tvgName = tvgName,
        epgId = tvgId,
        catchupType = catchup?.type?.name,
        catchupSource = catchup?.source,
        catchupDays = catchup?.days ?: 0,
        userAgent = userAgent,
        referrer = referrer,
    )

    private fun loadXtream(p: PlaylistEntity): Pair<List<ChannelEntity>, List<String>> {
        val account = XtreamAccount(p.url, p.username.orEmpty(), p.password.orEmpty())
        val login = JSONObject(Http.text(account.api()))
        val auth = login.optJSONObject("user_info")?.optInt("auth", 0) ?: 0
        if (auth != 1) throw IOException("Сервер не принял логин или пароль")
        val categories = JSONArray(Http.text(account.api("get_live_categories")))
        val groupNames = (0 until categories.length()).map { categories.getJSONObject(it) }
            .associate { it.optString("category_id") to it.optString("category_name") }
        val streams = JSONArray(Http.text(account.api("get_live_streams")))
        val channels = (0 until streams.length()).map { streams.getJSONObject(it) }.mapIndexed { i, s ->
            val archive = s.optInt("tv_archive", 0) == 1
            ChannelEntity(
                playlistId = p.id,
                position = i,
                name = s.optString("name").trim(),
                groupName = groupNames[s.optString("category_id")],
                logo = s.optString("stream_icon").ifBlank { null },
                url = account.liveUrl(s.optLong("stream_id")),
                tvgId = s.optString("epg_channel_id").ifBlank { null },
                tvgName = null,
                epgId = s.optString("epg_channel_id").ifBlank { null },
                catchupType = if (archive) Catchup.Type.XTREAM.name else null,
                catchupSource = null,
                catchupDays = if (archive) s.optInt("tv_archive_duration", 3) else 0,
                userAgent = null,
                referrer = null,
            )
        }
        return channels to listOf(account.epgUrl())
    }

    /**
     * Reads the XMLTV guide and keeps only programmes of this playlist's channels, from a week
     * back (for the archive) to two days ahead. Channels without tvg-id are matched by name.
     */
    suspend fun refreshEpg(id: Long) = withContext(Dispatchers.IO) {
        val playlist = dao.playlist(id) ?: return@withContext
        val urls = (playlist.epgOverride ?: playlist.epgDetected)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (urls.isEmpty()) return@withContext
        val channels = dao.channels(id)
        val now = System.currentTimeMillis()
        val from = now - 7 * DAY
        val to = now + 2 * DAY
        for (url in urls) {
            try {
                val guide = Http.open(url) { parseGuide(it, channels, from, to) }
                guide.matches.forEach { (channelId, epgId) -> dao.setEpgId(channelId, epgId) }
                val ids = guide.programs.map { it.epgId }.distinct()
                ids.chunked(500).forEach { dao.deletePrograms(it) }
                guide.programs.chunked(2_000).forEach { dao.insertPrograms(it) }
            } catch (e: Exception) {
                // One broken guide must not stop the next one; the channels still play.
            }
        }
        dao.deleteOldPrograms(from)
        dao.updatePlaylist((dao.playlist(id) ?: playlist).copy(epgUpdatedAt = now))
    }

    private class Guide(val programs: List<ProgramEntity>, val matches: Map<Long, String>)

    private fun parseGuide(input: InputStream, channels: List<ChannelEntity>, from: Long, to: Long): Guide {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val guideIds = HashSet<String>()
        val byName = HashMap<String, String>()
        var wanted: Set<String>? = null
        var matches: Map<Long, String> = emptyMap()
        val programs = ArrayList<ProgramEntity>()

        fun resolve() {
            val m = HashMap<Long, String>()
            for (c in channels) {
                val direct = c.tvgId?.takeIf { it in guideIds }
                val named = direct ?: listOfNotNull(c.tvgName, c.name).firstNotNullOfOrNull { byName[Epg.normalize(it)] }
                if (named != null) m[c.id] = named
            }
            matches = m
            wanted = m.values.toHashSet()
        }

        var channelId: String? = null
        var start = 0L
        var stop = 0L
        var programChannel: String? = null
        var title: String? = null
        var desc: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id")
                        channelId?.let { guideIds += it }
                    }
                    "display-name" -> if (channelId != null) {
                        val name = parser.nextText()
                        byName.putIfAbsent(Epg.normalize(name), channelId!!)
                    }
                    "programme" -> {
                        if (wanted == null) resolve()
                        programChannel = parser.getAttributeValue(null, "channel")
                        start = parser.getAttributeValue(null, "start")?.let(Epg::parseTime) ?: 0
                        stop = parser.getAttributeValue(null, "stop")?.let(Epg::parseTime) ?: 0
                        title = null; desc = null
                        val keep = programChannel?.let { it in wanted!! } ?: false
                        if (!keep || stop < from || start > to) {
                            skip(parser)
                            programChannel = null
                        }
                    }
                    "title" -> if (programChannel != null && title == null) title = parser.nextText()
                    "desc" -> if (programChannel != null && desc == null) desc = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        val ch = programChannel
                        if (ch != null && stop > start) {
                            programs += ProgramEntity(epgId = ch, start = start, stop = stop, title = title?.trim().orEmpty().ifEmpty { "Без названия" }, description = desc?.trim())
                        }
                        programChannel = null
                    }
                }
            }
            event = parser.next()
        }
        if (wanted == null) resolve()
        return Guide(programs, matches)
    }

    /** Skips the current element and its children. */
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
