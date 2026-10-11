package app.couchy.youtube

import app.couchy.core.youtube.SponsorBlock
import app.couchy.core.youtube.YouTubeFeed
import app.couchy.core.youtube.YouTubeLinks
import app.couchy.core.youtube.YtChannel
import app.couchy.core.youtube.YtVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.HttpURLConnection
import java.net.URL

/** One page of a list and the key to the next one. */
data class YtPage(val videos: List<YtVideo>, val channels: List<YtChannel> = emptyList(), val next: Page? = null)

/** What the player needs: the picture, the sound when it comes apart, other qualities and parts to skip. */
data class YtStream(
    val video: YtVideo,
    val uri: String,
    val audioUri: String?,
    val userAgent: String,
    /** Quality label and video link, best first. */
    val variants: List<Pair<String, String>>,
    val qualityLabel: String?,
    val skips: List<SponsorBlock.Segment>,
    val description: String,
    val related: List<YtVideo>,
    val subtitles: List<Pair<String, String>>,
)

/**
 * YouTube through NewPipe Extractor: the same pages the site shows, read without an account,
 * so there are no ads and nothing is tracked. Stream links come straight from YouTube's servers.
 */
object YouTubeClient {
    private val service get() = ServiceList.YouTube
    @Volatile private var ready = false

    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(Http, Localization("ru", "RU"), ContentCountry("RU"))
                ready = true
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { init(); block() }

    suspend fun search(query: String, page: Page? = null): YtPage = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.ALL), "")
        if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            pageOf(info.relatedItems, info.nextPage)
        } else {
            val more = SearchInfo.getMoreItems(service, handler, page)
            pageOf(more.items, more.nextPage)
        }
    }

    /** The kiosks YouTube offers in this country ("Trending", music, games…): id and Russian name. */
    suspend fun kiosks(): List<Pair<String, String>> = io {
        val list = service.kioskList
        list.availableKiosks.sortedBy { if (it == list.defaultKioskId) 0 else 1 }.map { it to kioskName(it) }
    }

    suspend fun kiosk(id: String?, page: Page? = null): YtPage = io {
        val list = service.kioskList
        val kioskId = id ?: list.defaultKioskId
        if (page == null) {
            val extractor = list.getExtractorById(kioskId, null)
            val info = KioskInfo.getInfo(extractor)
            pageOf(info.relatedItems, info.nextPage)
        } else {
            val url = list.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url
            val more = KioskInfo.getMoreItems(service, url, page)
            pageOf(more.items, more.nextPage)
        }
    }

    suspend fun channel(url: String): Pair<YtChannel, YtPage> = io {
        val info = ChannelInfo.getInfo(service, url)
        val channel = YtChannel(info.url, info.name, best(info.avatars, 176), info.subscriberCount)
        val tab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters } ?: info.tabs.firstOrNull()
        val page = tab?.let { t ->
            val videos = ChannelTabInfo.getInfo(service, t)
            pageOf(videos.relatedItems, videos.nextPage).let { p -> p.copy(videos = p.videos.map { v -> v.copy(channel = v.channel.ifBlank { channel.name }, channelUrl = channel.url) }) }
        } ?: YtPage(emptyList())
        channel to page
    }

    /** New videos of the channels followed, newest first, from their public feeds. */
    suspend fun subscriptionsFeed(channels: List<YtChannel>): List<YtVideo> = coroutineScope {
        channels.map { c ->
            async(Dispatchers.IO) {
                val id = c.id ?: return@async emptyList()
                runCatching { YouTubeFeed.parse(Http.text(YouTubeLinks.feedUrl(id), DESKTOP_UA), c.url) }.getOrDefault(emptyList())
                    .map { it.copy(channel = it.channel.ifBlank { c.name }) }
            }
        }.awaitAll().flatten().sortedByDescending { it.publishedMs }
    }

    suspend fun stream(link: String): YtStream = io {
        val info = StreamInfo.getInfo(service, link)
        val video = YtVideo(
            id = YouTubeLinks.videoId(info.url) ?: info.id,
            title = info.name,
            channel = info.uploaderName.orEmpty(),
            channelUrl = info.uploaderUrl,
            durationSec = info.duration,
            views = info.viewCount,
            live = info.streamType == StreamType.LIVE_STREAM,
        )
        val skips = if (video.live) emptyList() else runCatching { SponsorBlock.parse(Http.text(SponsorBlock.url(video.id), DESKTOP_UA)) }.getOrDefault(emptyList())
        val related = info.relatedItems.mapNotNull { (it as? StreamInfoItem)?.let(::videoOf) }
        val subtitles = runCatching {
            info.subtitles.filter { !it.isAutoGenerated && it.isUrl }.map { (it.displayLanguageName ?: it.languageTag) to it.content }
        }.getOrDefault(emptyList())
        val description = info.description?.content.orEmpty().replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")

        // Separate picture and sound give every quality up to 4K; mp4 first, as TV boxes decode it best.
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.audioLocale?.language == "ru" || it.audioTrackType == null }
                .thenByDescending { it.format?.mimeType?.contains("mp4") == true }
                .thenByDescending { it.averageBitrate })
            .firstOrNull()
        val pictures = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height > 0 }
            .groupBy { it.height }
            .mapNotNull { (_, same) -> same.sortedByDescending { it.format?.mimeType?.contains("mp4") == true && it.codec?.startsWith("av01") != true }.firstOrNull() }
            .sortedByDescending { it.height }
        val variants = pictures.map { label(it.height, it.fps) to it.content }

        when {
            // Live streams come as HLS with every quality inside.
            video.live && !info.hlsUrl.isNullOrBlank() ->
                YtStream(video, info.hlsUrl, null, agentFor(info.hlsUrl), emptyList(), null, skips, description, related, subtitles)
            audio != null && variants.isNotEmpty() -> {
                val wanted = QualityPick.choose(variants.map { it.first })
                val chosen = variants.firstOrNull { it.first == wanted } ?: variants.first()
                YtStream(video, chosen.second, audio.content, agentFor(audio.content), variants, chosen.first, skips, description, related, subtitles)
            }
            !info.hlsUrl.isNullOrBlank() ->
                YtStream(video, info.hlsUrl, null, agentFor(info.hlsUrl), emptyList(), null, skips, description, related, subtitles)
            else -> {
                val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
                    ?: throw IllegalStateException("YouTube не отдал видео. Попробуйте ещё раз.")
                YtStream(video, muxed.content, null, agentFor(muxed.content), emptyList(), label(muxed.height, muxed.fps), skips, description, related, subtitles)
            }
        }
    }

    private fun agentFor(url: String): String = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
        else -> DESKTOP_UA
    }

    private fun label(height: Int, fps: Int): String {
        val base = when {
            height >= 2000 -> "4K"
            height >= 1400 -> "1440p"
            else -> "${height}p"
        }
        return if (fps > 30) "$base$fps" else base
    }

    private fun pageOf(items: List<InfoItem>, next: Page?): YtPage = YtPage(
        videos = items.mapNotNull { (it as? StreamInfoItem)?.takeIf { s -> !s.isShortFormContent }?.let(::videoOf) },
        channels = items.mapNotNull { (it as? ChannelInfoItem)?.let { c -> YtChannel(c.url, c.name, best(c.thumbnails, 176), c.subscriberCount) } },
        next = next?.takeIf { Page.isValid(it) },
    )

    private fun videoOf(s: StreamInfoItem): YtVideo {
        val id = YouTubeLinks.videoId(s.url) ?: s.url
        return YtVideo(
            id = id,
            title = s.name,
            channel = s.uploaderName.orEmpty(),
            channelUrl = s.uploaderUrl,
            durationSec = s.duration.coerceAtLeast(0),
            views = s.viewCount,
            ago = s.textualUploadDate,
            publishedMs = runCatching { s.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: 0,
            live = s.streamType == StreamType.LIVE_STREAM,
            thumb = best(s.thumbnails, 360) ?: YouTubeLinks.thumb(id),
        )
    }

    /** The smallest picture at least [height] high, so lists stay light. */
    private fun best(images: List<Image>, height: Int): String? =
        images.filter { it.height >= height }.minByOrNull { it.height }?.url ?: images.maxByOrNull { it.height }?.url

    private fun kioskName(id: String) = when (id.lowercase()) {
        "trending" -> "В тренде"
        "trending_music", "music" -> "Музыка"
        "trending_gaming", "gaming" -> "Игры"
        "trending_movies_and_shows", "movies" -> "Фильмы и шоу"
        "trending_podcasts_episodes", "podcasts" -> "Подкасты"
        "live" -> "Трансляции"
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** NewPipe's network: plain HttpURLConnection with the headers it asks for. */
    private object Http : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", DESKTOP_UA)
            request.headers().forEach { (name, values) ->
                c.setRequestProperty(name, values.joinToString(", "))
            }
            request.dataToSend()?.let { body ->
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            try {
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("YouTube просит подтвердить, что вы не робот", request.url())
                return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, text, c.url.toString())
            } finally {
                c.disconnect()
            }
        }

        fun text(url: String, agent: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", agent)
            return try {
                if (c.responseCode != 200) "" else c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
    }
}

/** The video to start with: the quality wanted, or the best one not above it, or the best there is. */
object QualityPick {
    fun choose(labels: List<String>): String? {
        val label = app.couchy.QualityChoice.label ?: return labels.firstOrNull()
        labels.firstOrNull { it == label }?.let { return it }
        val target = heightOf(label).takeIf { it > 0 } ?: return labels.firstOrNull()
        return labels.filter { heightOf(it) in 1..target }.maxByOrNull { heightOf(it) } ?: labels.lastOrNull()
    }

    fun heightOf(label: String): Int = when {
        label.startsWith("4K", ignoreCase = true) || label.startsWith("2160") -> 2160
        label.startsWith("2K", ignoreCase = true) -> 1440
        else -> Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
