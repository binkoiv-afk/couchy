package app.stereoplay

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

data class Video(val uri: Uri, val title: String)

class MainActivity : ComponentActivity() {
    lateinit var prefs: Prefs; private set
    lateinit var engine: DepthEngine; private set
    lateinit var player: ExoPlayer; private set

    private var video by mutableStateOf<Video?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = Prefs(this)
        engine = DepthEngine(applicationContext)
        player = buildPlayer()
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7FD3FF), secondary = Color(0xFFFF7FA8))) {
                val v = video
                if (v == null) {
                    HomeScreen(
                        recent = prefs.recent(),
                        onOpen = { uri, title -> open(uri, title) },
                    )
                } else {
                    PlayerScreen(
                        activity = this,
                        video = v,
                        onBack = { close() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        engine.close()
    }

    fun open(uri: Uri, title: String? = null) {
        savePosition()
        val name = title ?: displayName(uri)
        prefs.addRecent(uri, name)
        video = Video(uri, name)
        engine.resetTemporal()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        val pos = prefs.position(uri)
        if (pos > 0) player.seekTo(pos)
        player.playWhenReady = true
        enterPlayer(true)
    }

    fun close() {
        savePosition()
        player.stop()
        player.clearMediaItems()
        video = null
        enterPlayer(false)
    }

    fun savePosition() {
        val v = video ?: return
        val dur = player.duration
        val pos = player.currentPosition
        // Nearly finished films start over next time.
        val keep = if (dur != C.TIME_UNSET && dur > 0 && pos > dur - 60_000) 0L else pos
        prefs.savePosition(v.uri, keep)
    }

    private fun enterPlayer(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> streamExtra(intent)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    Regex("""https?://\S+""").find(text)?.value?.let(Uri::parse)
                }
            else -> null
        }
        if (uri != null) open(uri, intent.getStringExtra("title"))
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return Uri.decode(uri.lastPathSegment ?: uri.toString())
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) StereoPlay")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { it.repeatMode = Player.REPEAT_MODE_OFF }
    }
}
