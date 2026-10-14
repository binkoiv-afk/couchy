package app.couchy.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Frames for the timeline while seeking, read straight from the video file. Works for whole
 * files (HDrezka's mp4, torrents, downloads); streams cut into pieces (HLS, DASH) and live
 * channels get none, and a file that will not give frames is left alone after the first try.
 */
class Thumbs(private val context: Context, private val uri: String, private val headers: Map<String, String>) {
    private val lock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var broken = false
    @Volatile private var closed = false
    private val cache = LruCache<Long, ImageBitmap>(40)

    /** The frame near [positionMs], rounded to 10 seconds; null when there is none to show. */
    suspend fun at(positionMs: Long): ImageBitmap? {
        val bucket = positionMs / STEP_MS * STEP_MS
        cache.get(bucket)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    read(bucket)
                } finally {
                    if (closed) close()
                }
            }
        }
    }

    private fun read(bucket: Long): ImageBitmap? {
        if (broken || closed) return null
        cache.get(bucket)?.let { return it }
        val r = open() ?: return null
        val frame = runCatching { frame(r, bucket) }.getOrNull()
        if (frame == null) {
            // One miss on a file that never gave a frame means it never will: stop asking.
            if (cache.size() == 0) broken = true
            return null
        }
        return frame.asImageBitmap().also { cache.put(bucket, it) }
    }

    private fun open(): MediaMetadataRetriever? {
        retriever?.let { return it }
        return runCatching {
            MediaMetadataRetriever().apply {
                when {
                    uri.startsWith("http") -> setDataSource(uri, headers)
                    uri.startsWith("/") -> setDataSource(uri)
                    else -> setDataSource(context, Uri.parse(uri))
                }
            }
        }.onFailure { broken = true }.getOrNull()?.also { retriever = it }
    }

    private fun frame(r: MediaMetadataRetriever, ms: Long): Bitmap? {
        val us = ms * 1000
        return if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH * 9 / 16)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                Bitmap.createScaledBitmap(full, WIDTH, WIDTH * full.height / full.width.coerceAtLeast(1), true).also { if (it !== full) full.recycle() }
            }
        }
    }

    /** Frees the file; one being read right now is freed when its frame is done. */
    fun release() {
        closed = true
        if (lock.tryLock()) {
            try { close() } finally { lock.unlock() }
        }
    }

    private fun close() {
        runCatching { retriever?.release() }
        retriever = null
    }

    companion object {
        private const val STEP_MS = 10_000L
        private const val WIDTH = 320

        /** Whether [uri] is a whole file whose frames can be read. */
        fun supported(uri: String): Boolean {
            val u = uri.lowercase().substringBefore('?')
            if (u.endsWith(".m3u8") || u.endsWith(".mpd") || u.contains(":hls:") || u.contains("/manifest")) return false
            return u.startsWith("http") || u.startsWith("file:") || u.startsWith("content:") || u.startsWith("/")
        }
    }
}
