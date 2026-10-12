package app.couchy.episodes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.couchy.BuildConfig
import app.couchy.CouchyApp
import app.couchy.MainActivity
import app.couchy.R
import app.couchy.core.sources.Cinemeta
import app.couchy.core.sources.Episodes
import app.couchy.core.sources.Title
import app.couchy.core.sources.TitleJson
import app.couchy.core.sources.TitleKind
import app.couchy.data.Marks
import app.couchy.sources.Web
import org.json.JSONArray
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Looks for new episodes of the series the person follows, a few times a day, and posts a
 * notification that opens the series in Couchy.
 */
object NewEpisodes {
    const val EXTRA_TITLE = "couchy.title"
    private const val CHANNEL = "episodes"
    private const val WORK = "new-episodes"

    /** Followed series; one put in "Смотреть позже" or "Любимое" gets followed as well. */
    fun followed(marks: Marks): List<Title> =
        marks.titles(Marks.FOLLOW)
            .filter { it.kind == TitleKind.SERIES && it.imdbId != null }
            .distinctBy { it.imdbId }

    /** Films marked «Жду»: announced once they can be watched at home, then let go. */
    fun awaited(marks: Marks): List<Title> =
        marks.titles(Marks.FOLLOW).filter { it.kind == TitleKind.MOVIE && it.imdbId != null }.distinctBy { it.imdbId }

    fun schedule(context: Context) {
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Worker>(6, TimeUnit.HOURS).setConstraints(network).build(),
        )
    }

    /** A look right away, so a newly followed series gets its count remembered. */
    fun checkSoon(context: Context) {
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<Worker>().setConstraints(network).build())
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as CouchyApp
            val seen = app.getSharedPreferences("episodes", Context.MODE_PRIVATE)
            val web = Web(app)
            val now = System.currentTimeMillis()
            for (title in followed(app.marks)) {
                val imdb = title.imdbId ?: continue
                val list = runCatching { Episodes.parseCinemeta(web.get(Cinemeta.metaUrl(imdb, TitleKind.SERIES))) }.getOrNull() ?: continue
                if (list.isEmpty()) continue
                val known = seen.getInt(imdb, -1).takeIf { it >= 0 }
                val fresh = Episodes.fresh(known, list, now)
                seen.edit().putInt(imdb, Episodes.aired(list, now).size).apply()
                Episodes.announce(fresh)?.let { text -> notify(app, title, text) }
            }
            val key = app.settings.prefs.first().tmdbKey.ifBlank { BuildConfig.TMDB_KEY }
            for (film in awaited(app.marks)) {
                val home = runCatching { homeRelease(web, key, film.imdbId!!) }.getOrNull() ?: continue
                if (home <= now) {
                    notify(app, film, "Вышел в цифре, можно смотреть")
                    app.marks.toggleTitle(Marks.FOLLOW, film)
                }
            }
            return Result.success()
        }

        /** When the film can be watched at home: TMDB's digital release, or Cinemeta's without a key. */
        private suspend fun homeRelease(web: Web, key: String, imdb: String): Long? {
            if (key.isNotBlank()) {
                val (findUrl, h) = app.couchy.core.sources.Tmdb.request(key, "/find/$imdb", mapOf("external_source" to "imdb_id"))
                val id = app.couchy.core.sources.Tmdb.parseFind(web.get(findUrl, h))?.first
                if (id != null) {
                    val (url, headers) = app.couchy.core.sources.Tmdb.request(key, "/movie/$id/release_dates")
                    app.couchy.core.sources.Releases.homeRelease(web.get(url, headers))?.let { return it }
                }
            }
            return app.couchy.core.sources.Releases.cinemetaHome(web.get(Cinemeta.metaUrl(imdb, TitleKind.MOVIE)))
        }
    }

    private fun notify(context: Context, title: Title, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TITLE, TitleJson.encode(listOf(title)).toString())
        val id = title.imdbId.hashCode()
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.name)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /** The series a notification was tapped on. */
    fun titleOf(intent: Intent?): Title? = intent?.getStringExtra(EXTRA_TITLE)
        ?.let { runCatching { TitleJson.decode(JSONArray(it)).firstOrNull() }.getOrNull() }
}
