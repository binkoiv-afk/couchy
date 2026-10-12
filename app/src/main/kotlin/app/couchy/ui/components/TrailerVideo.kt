package app.couchy.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * A trailer playing without sound behind a banner, fading in over the picture once the first
 * frame is there. [onEnd] is called when it finishes or fails.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerVideo(url: String, userAgent: String, modifier: Modifier = Modifier, maxMs: Long = 45_000, onEnd: () -> Unit) {
    val context = LocalContext.current
    var shown by remember(url) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(900), label = "trailer")
    val player = remember(url) {
        val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            volume = 0f
            setMediaItem(
                MediaItem.Builder().setUri(url)
                    // The opening seconds are usually studio logos.
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(4_000).setEndPositionMs(4_000 + maxMs).build())
                    .build(),
            )
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { shown = true }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { shown = false; onEnd() } }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { shown = false; onEnd() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        },
        modifier = modifier.alpha(alpha),
    )
}
