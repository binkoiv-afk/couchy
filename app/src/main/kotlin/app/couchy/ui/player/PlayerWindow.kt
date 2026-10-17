package app.couchy.ui.player

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Rational
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import app.couchy.MainActivity
import app.couchy.R
import kotlinx.coroutines.flow.MutableStateFlow

/** The player in a small window over other apps, on phones and tablets. */
object PlayerWindow {
    /** True while Couchy is shown as a picture-in-picture window. */
    val pip = MutableStateFlow(false)

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun enter(activity: Activity): Boolean {
        if (!supported(activity) || Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }.getOrDefault(false)
    }
}

/**
 * YouTube keeps playing with the screen off or Couchy in the background, like YouTube Premium.
 * The player stays where it is; this service only keeps Couchy alive and shows the controls.
 */
object BackgroundAudio {
    @Volatile var player: Player? = null
    @Volatile var title: String = ""
    /** The player's session: with it the notification is the system's media card, as in music apps. */
    @Volatile var session: androidx.media3.session.MediaSession? = null
    private var watched: Player? = null
    private var appContext: Context? = null

    /** Play and pause redraw the notification's button. */
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { refresh() }
    }

    fun start(context: Context, player: Player, title: String, session: androidx.media3.session.MediaSession? = null) {
        this.player = player
        this.title = title
        if (session != null) this.session = session
        appContext = context.applicationContext
        if (watched !== player) {
            watched?.removeListener(listener)
            player.addListener(listener)
            watched = player
        }
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, BackgroundAudioService::class.java)) }
    }

    fun stop(context: Context) {
        watched?.removeListener(listener)
        watched = null
        player = null
        runCatching { context.stopService(Intent(context, BackgroundAudioService::class.java)) }
    }

    private fun refresh() {
        val c = appContext ?: return
        if (player == null) return
        runCatching { c.getSystemService(NotificationManager::class.java).notify(BackgroundAudioService.ID, BackgroundAudioService.notification(c)) }
    }
}

class BackgroundAudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> BackgroundAudio.player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_NEXT -> BackgroundAudio.session?.player?.seekToNext()
            ACTION_PREVIOUS -> BackgroundAudio.session?.player?.seekToPrevious()
            ACTION_STOP -> {
                BackgroundAudio.player?.pause()
                BackgroundAudio.player = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(this), type) }
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 7302
        private const val CHANNEL = "background"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_STOP = "stop"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "previous"

        fun notification(context: Context): Notification = with(context) {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Фоновое воспроизведение", NotificationManager.IMPORTANCE_LOW))
            }
            fun action(name: String, code: Int) = PendingIntent.getService(
                this, code, Intent(this, BackgroundAudioService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val session = BackgroundAudio.session
            val playing = BackgroundAudio.player?.isPlaying == true
            val title = BackgroundAudio.title.ifBlank { "Couchy" }
            val (song, artist) = songParts(title)
            val builder = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(song)
                .setContentText(artist.ifBlank { "Couchy" })
                .setContentIntent(open)
                .setOngoing(playing)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDeleteIntent(action(ACTION_STOP, 2))
            if (session != null) {
                // The system's media card: cover, timeline and buttons in the shade and on the lock screen.
                builder
                    .addAction(android.R.drawable.ic_media_previous, "Предыдущая", action(ACTION_PREVIOUS, 3))
                    .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Пауза" else "Играть", action(ACTION_TOGGLE, 1))
                    .addAction(android.R.drawable.ic_media_next, "Следующая", action(ACTION_NEXT, 4))
                    .setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1, 2))
            } else {
                builder
                    .addAction(0, "Пауза / играть", action(ACTION_TOGGLE, 1))
                    .addAction(0, "Остановить", action(ACTION_STOP, 2))
            }
            builder.build()
        }
    }
}
