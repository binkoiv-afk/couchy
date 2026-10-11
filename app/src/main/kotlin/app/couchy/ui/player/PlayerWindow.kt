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

    fun start(context: Context, player: Player, title: String) {
        this.player = player
        this.title = title
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, BackgroundAudioService::class.java)) }
    }

    fun stop(context: Context) {
        player = null
        runCatching { context.stopService(Intent(context, BackgroundAudioService::class.java)) }
    }
}

class BackgroundAudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> BackgroundAudio.player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_STOP -> {
                BackgroundAudio.player?.pause()
                BackgroundAudio.player = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, ID, notification(), type) }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(BackgroundAudio.title.ifBlank { "Couchy" })
            .setContentText("Играет в фоне")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Пауза / играть", action(ACTION_TOGGLE, 1))
            .addAction(0, "Остановить", action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        private const val ID = 7302
        private const val CHANNEL = "background"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_STOP = "stop"
    }
}
