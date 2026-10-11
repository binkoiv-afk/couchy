package app.couchy.tvhome

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.couchy.MainActivity
import app.couchy.core.Progress
import app.couchy.core.youtube.YouTubeLinks
import app.couchy.data.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Продолжить просмотр" on the Android TV home screen: what was stopped halfway shows in the
 * system's Watch Next row and opens straight into Couchy's player at the same place.
 */
object TvHome {
    private const val LIMIT = 12

    fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    @SuppressLint("RestrictedApi")
    suspend fun publish(context: Context, library: List<MediaEntity>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26 || !isTv(context)) return@withContext
        val wanted = library
            .filter { it.positionMs > 60_000 && it.durationMs > 0 && !Progress.isWatched(it.positionMs, it.durationMs) }
            .sortedByDescending { it.lastWatchedAt }
            .take(LIMIT)
        val resolver = context.contentResolver
        val ours = HashMap<String, Long>()
        runCatching {
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = WatchNextProgram.fromCursor(c)
                    p.internalProviderId?.let { ours[it] = p.id }
                }
            }
        }.onFailure { return@withContext }
        val keep = wanted.map { it.uri }.toSet()
        ours.filterKeys { it !in keep }.values.forEach { id ->
            runCatching { resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
        }
        wanted.forEach { m ->
            val open = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(m.uri))
            val program = WatchNextProgram.Builder()
                .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(m.lastWatchedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                .setTitle(m.title)
                .setPosterArtUri(poster(context, m))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
                .setLastPlaybackPositionMillis(m.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(m.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setInternalProviderId(m.uri)
                .build()
            runCatching {
                val id = ours[m.uri]
                if (id != null) resolver.update(TvContractCompat.buildWatchNextProgramUri(id), program.toContentValues(), null, null)
                else resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
            }
        }
    }

    /** YouTube's own picture, or a card with the title drawn on it. */
    private fun poster(context: Context, m: MediaEntity): Uri {
        YouTubeLinks.videoId(m.uri)?.let { return Uri.parse(YouTubeLinks.thumb(it)) }
        val name = "${m.uri.hashCode().toUInt()}.png"
        val file = File(PosterProvider.dir(context), name)
        if (!file.exists()) runCatching { file.outputStream().use { draw(m.title).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return Uri.parse("content://${context.packageName}.posters/$name")
    }

    private fun draw(title: String): Bitmap {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = (title.hashCode() and 0xFFFFFF) % 360
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.65f, 0.18f))
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 46f
            isFakeBoldText = true
        }
        val layout = StaticLayout(title, paint, w - 80, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false)
        canvas.save()
        canvas.translate(40f, (h - layout.height.coerceAtMost(h - 60)) - 36f)
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }
}

/** Hands the title cards to the TV home screen, which reads them from another app. */
class PosterProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment?.takeIf { Regex("""\d+\.png""").matches(it) } ?: return null
        val file = File(dir(ctx), name)
        if (!file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun dir(context: Context) = File(context.cacheDir, "posters").apply { mkdirs() }
    }
}
