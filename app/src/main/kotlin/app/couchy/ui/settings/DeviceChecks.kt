package app.couchy.ui.settings

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
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
    suspend fun testVideo(context: Context): String? = withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(context).build()
        try {
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
            player.prepare()
            player.playWhenReady = true
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<String?> { c ->
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && c.isActive) c.resume(null)
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            if (c.isActive) c.resume(error.errorCodeName + (error.cause?.let { " · ${it.javaClass.simpleName}: ${it.message?.take(90)}" } ?: ""))
                        }
                    })
                }
            } ?: "не загрузилось за 20 секунд"
        } finally {
            player.release()
        }
    }
}
