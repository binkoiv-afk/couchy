package app.couchy.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.couchy.R
import app.couchy.core.youtube.Chapter
import app.couchy.core.youtube.Chapters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * "Сохранить звук": a YouTube video's sound saved to Music/Couchy as music files, one per chapter
 * when the video has them (an album, a mix, a long podcast). The sound is kept as YouTube sends
 * it, AAC in .m4a, so it is as good as the original and is saved in seconds, without re-encoding.
 */
object AudioSave {
    const val CHANNEL = "audio-save"

    fun start(context: Context, link: String, title: String) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setInputData(workDataOf("link" to link, "title" to title))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("audio:$link", ExistingWorkPolicy.KEEP, request)
    }

    /** A whole album, track after track, into one folder named after it. */
    fun album(context: Context, album: String, tracks: List<Pair<String, String>>) {
        if (tracks.isEmpty()) return
        val requests = tracks.mapIndexed { i, (link, title) ->
            OneTimeWorkRequestBuilder<Worker>()
                .setInputData(workDataOf("link" to link, "title" to title, "album" to album, "number" to i + 1, "of" to tracks.size))
                .build()
        }
        requests.drop(1).fold(WorkManager.getInstance(context).beginUniqueWork("album:$album", ExistingWorkPolicy.KEEP, requests.first())) { chain, r -> chain.then(r) }.enqueue()
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        private val id = (inputData.getString("link") ?: "").hashCode()

        override suspend fun doWork(): Result {
            val link = inputData.getString("link") ?: return Result.failure()
            val name = inputData.getString("title").orEmpty()
            val cache = File(applicationContext.cacheDir, "audio").apply { mkdirs() }
            val whole = File(cache, "${id.toUInt()}.src")
            return try {
                progress(name, "Готовлю…", null)
                val audio = YouTubeClient.audio(link)
                download(audio, whole) { done, total -> progress(audio.title, "Скачиваю звук", if (total > 0) (done * 100 / total).toInt() else null) }
                // A track of an album being saved: one file, numbered, in the album's folder.
                val ofAlbum = inputData.getString("album")
                val tracks = audio.chapters.takeIf { it.size >= 2 && ofAlbum == null } ?: listOf(Chapter(0, Long.MAX_VALUE, audio.title))
                val album = Chapters.safe(ofAlbum ?: audio.title)
                val ext = if (audio.mp4) "m4a" else "webm"
                tracks.forEachIndexed { i, ch ->
                    progress(audio.title, "Трек ${i + 1} из ${tracks.size}", (i * 100) / tracks.size)
                    val part = File(cache, "${id.toUInt()}-$i.$ext")
                    cut(whole, part, ch, audio.mp4)
                    val fileName = when {
                        ofAlbum != null -> Chapters.fileName(inputData.getInt("number", 1), inputData.getInt("of", 1), name.ifBlank { audio.title }, ext)
                        tracks.size == 1 -> "${album}.$ext"
                        else -> Chapters.fileName(i + 1, tracks.size, ch.title, ext)
                    }
                    publish(part, album, fileName, if (audio.mp4) "audio/mp4" else "audio/webm")
                    part.delete()
                }
                if (ofAlbum != null) {
                    val n = inputData.getInt("number", 1)
                    val of = inputData.getInt("of", 1)
                    if (n == of) done(ofAlbum, "Альбом сохранён в «Музыка/Couchy/$album»")
                    return Result.success()
                }
                done(audio.title, if (tracks.size == 1) "Сохранено в «Музыка/Couchy»" else "${tracks.size} треков в «Музыка/Couchy/$album»")
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                done(name, "Не получилось сохранить звук: ${e.message ?: "ошибка"}")
                // One track that would not save does not stop the rest of the album.
                if (inputData.getString("album") != null) Result.success() else Result.failure()
            } finally {
                whole.delete()
            }
        }

        /** In pieces of a few megabytes: YouTube slows down one long request, not many short ones. */
        private suspend fun download(audio: YtAudio, to: File, onProgress: suspend (Long, Long) -> Unit) {
            val chunk = 4L shl 20
            var total = -1L
            var at = 0L
            to.outputStream().use { out ->
                while (total < 0 || at < total) {
                    val c = URL(audio.url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    c.setRequestProperty("User-Agent", audio.userAgent)
                    c.setRequestProperty("Range", "bytes=$at-${at + chunk - 1}")
                    try {
                        val code = c.responseCode
                        if (code !in 200..299) throw IllegalStateException("YouTube ответил $code")
                        if (total < 0) {
                            total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: c.contentLengthLong.takeIf { code == 200 && it > 0 } ?: throw IllegalStateException("неизвестный размер")
                        }
                        val got = c.inputStream.use { it.copyTo(out) }
                        if (got == 0L) throw IllegalStateException("обрыв связи")
                        at += got
                        if (code == 200) at = total
                    } finally {
                        c.disconnect()
                    }
                    onProgress(at, total)
                }
            }
        }

        /** One chapter of the sound into its own file, the frames copied as they are. */
        private fun cut(from: File, to: File, ch: Chapter, mp4: Boolean) {
            val extractor = MediaExtractor()
            extractor.setDataSource(from.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val muxer = MediaMuxer(to.path, if (mp4) MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 else MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            try {
                val out = muxer.addTrack(format)
                muxer.start()
                val startUs = ch.startMs * 1000
                val endUs = if (ch.endMs == Long.MAX_VALUE) Long.MAX_VALUE else ch.endMs * 1000
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val buffer = ByteBuffer.allocate(1 shl 20)
                val info = MediaCodec.BufferInfo()
                var first = -1L
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val time = extractor.sampleTime
                    if (time >= endUs) break
                    if (time >= startUs - 50_000) {
                        if (first < 0) first = time
                        info.set(0, size, time - first, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(out, buffer, info)
                    }
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
                extractor.release()
            }
        }

        /** Into the phone's music, where players find it: Music/Couchy/<video>. */
        private fun publish(file: File, album: String, fileName: String, mime: String) {
            val context = applicationContext
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Couchy/$album")
                    put(MediaStore.Audio.Media.TITLE, fileName.substringBeforeLast('.').replace(Regex("""^\d+\. """), ""))
                    put(MediaStore.Audio.Media.ALBUM, album)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("нет доступа к «Музыке»")
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } else {
                // Older Android: Couchy's own music folder, which needs no permission and players still see.
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Couchy/$album").apply { mkdirs() }
                val target = File(dir, fileName)
                file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            }
        }

        private fun channel() {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Сохранение звука", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private suspend fun progress(title: String, text: String, percent: Int?) {
            channel()
            val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.ifBlank { "Сохраняю звук" })
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent ?: 0, percent == null)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            runCatching { setForeground(ForegroundInfo(id, n, type)) }
                .onFailure { runCatching { NotificationManagerCompat.from(applicationContext).notify(id, n) } }
        }

        private fun done(title: String, text: String) {
            channel()
            val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.ifBlank { "Звук" })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(applicationContext).notify(id + 1, n) }
        }
    }
}
