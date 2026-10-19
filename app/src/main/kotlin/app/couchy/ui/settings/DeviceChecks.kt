package app.couchy.ui.settings

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The last video that would not play here, with the technical reason, for «Проверка связи». */
object LastPlayError {
    @Volatile var text: String? = null
}

/** What this device is and can play: the things that differ between a phone and a cheap projector. */
internal object DeviceChecks {
    fun device(context: Context): String {
        val m = context.resources.displayMetrics
        return listOf(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            "экран ${m.widthPixels}×${m.heightPixels}",
            "время ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}",
        ).joinToString(" · ")
    }

    fun webView(context: Context): Pair<Boolean, String> {
        val p = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
            ?: return false to "Нет WebView: сайты, которым нужна проверка «я не робот», не откроются."
        val major = p.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        return (major >= 90) to ("${p.packageName} ${p.versionName}" + if (major in 1 until 90) ". Старая версия: обновите «Android System WebView»." else "")
    }

    /** Opens the store page of «Android System WebView»: the app store if there is one, else the web page. */
    fun updateWebView(context: Context) {
        val id = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull()
            ?.takeIf { it.startsWith("com.google") } ?: "com.google.android.webview"
        val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$id"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(market) }.isSuccess) return
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            android.widget.Toast.makeText(context, "Магазина приложений нет. Установите «Android System WebView» с флешки.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** H.264 and HEVC decoders and how big a picture they take. */
    fun decoders(): Pair<Boolean, String> = runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        fun describe(mime: String): String? {
            val list = infos.filter { info -> info.supportedTypes.any { it.equals(mime, true) } }
            if (list.isEmpty()) return null
            return list.joinToString(", ") { info ->
                val caps = info.getCapabilitiesForType(mime).videoCapabilities
                "${info.name} до ${caps.supportedWidths.upper}×${caps.supportedHeights.upper}"
            }
        }
        val avc = describe("video/avc")
        val hevc = describe("video/hevc")
        (avc != null) to listOf("H.264: ${avc ?: "нет"}", "HEVC: ${hevc ?: "нет"}").joinToString("\n")
    }.getOrElse { false to "Не удалось прочитать: ${it.message}" }

    /** Plays a public test stream for a moment: null when it starts, else why not. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun testVideo(context: Context): String? {
        val url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val played = withContext(Dispatchers.Main) {
            // No retries and short timeouts, so a failure shows its real reason instead of a long wait.
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000)
            val noRetry = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
            }
            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http))
                        .setLoadErrorHandlingPolicy(noRetry),
                )
                .build()
            try {
                player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.playWhenReady = true
                withTimeoutOrNull(25_000) {
                    suspendCancellableCoroutine<String?> { c ->
                        player.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY && c.isActive) c.resume(null)
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                val causes = generateSequence(error.cause) { it.cause }.take(3)
                                    .joinToString(" · ") { e -> e.javaClass.simpleName + (e.message?.take(80)?.let { ": $it" } ?: "") }
                                if (c.isActive) c.resume(error.errorCodeName + if (causes.isNotEmpty()) " · $causes" else "")
                            }
                        })
                    }
                } ?: "не загрузилось за 25 секунд"
            } finally {
                player.release()
            }
        } ?: return null
        // Which way the network gets the stream: straight, and the way the app sends it (the bypass may take it).
        val straight = fetch(url, java.net.Proxy.NO_PROXY)
        val routed = fetch(url, null)
        return "$played\nНапрямую: ${straight ?: "открывается"}. Как ходит Couchy: ${routed ?: "открывается"}."
    }

    /** Null when [url] answers within 8 seconds, else why not. */
    private suspend fun fetch(url: String, proxy: java.net.Proxy?): String? = withContext(Dispatchers.IO) {
        runCatching {
            val u = java.net.URL(url)
            val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as java.net.HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                val code = c.responseCode
                c.inputStream.use { it.read(ByteArray(4096)) }
                if (code == 200) null else "ответ $code"
            } finally {
                c.disconnect()
            }
        }.getOrElse { e -> e.javaClass.simpleName + (e.message?.take(80)?.let { ": $it" } ?: "") }
    }
}
