package app.couchy.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.couchy.core.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryRepository(private val context: Context, private val dao: MediaDao) {
    val all = dao.observeAll()

    suspend fun get(uri: String): MediaEntity? = dao.get(uri)

    /** Every entry, for sync. */
    suspend fun snapshot(): List<MediaEntity> = dao.all()

    /** Writes an entry as sync merged it. */
    suspend fun put(entity: MediaEntity) = dao.upsert(entity)

    /**
     * Finds the entry for [link] or adds a new one. A known link keeps its history; a fresh
     * one gets a title from [title], the file's display name, or the link itself.
     */
    suspend fun open(link: String, title: String? = null): MediaEntity = withContext(Dispatchers.IO) {
        val uri = link.trim()
        dao.get(uri)?.let { return@withContext it }
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "content") keepAccess(parsed)
        val entity = MediaEntity(
            uri = uri,
            title = title?.takeIf { it.isNotBlank() } ?: displayName(parsed) ?: Links.titleFor(uri),
            kind = Links.classify(uri).name,
            addedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        entity
    }

    /** A .torrent picked from storage or shared by another app, which has no telling extension in its uri. */
    fun isTorrentFile(link: String): Boolean {
        val uri = Uri.parse(link)
        if (uri.scheme != "content") return false
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return type == "application/x-bittorrent" || displayName(uri, raw = true)?.endsWith(".torrent", ignoreCase = true) == true
    }

    suspend fun saveProgress(uri: String, positionMs: Long, durationMs: Long) =
        dao.updateProgress(uri, positionMs, durationMs, System.currentTimeMillis())

    suspend fun saveTracks(uri: String, audio: String?, subtitles: String?) = dao.updateTracks(uri, audio, subtitles)

    suspend fun setFavorite(uri: String, favorite: Boolean) = dao.setFavorite(uri, favorite)

    suspend fun clearProgress(uri: String) = dao.clearProgress(uri)

    /**
     * Whether a file on the device or one shared by another app is an IPTV list. Its uri rarely
     * ends in .m3u, so the first kilobytes decide.
     */
    suspend fun isPlaylistFile(link: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(link.trim())
        if (uri.scheme != "content" && uri.scheme != "file") return@withContext false
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            }
        }.getOrNull() ?: return@withContext false
        Links.sniff(head) == app.couchy.core.LinkKind.PLAYLIST
    }

    /**
     * Copies a playlist file into Couchy's own storage: the permission to read another app's
     * file ends with the visit, but the channels have to refresh later too. Returns a file:// link.
     */
    suspend fun keepPlaylistFile(link: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(link.trim())
        val name = (displayName(uri, raw = true) ?: uri.lastPathSegment ?: "playlist")
            .substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").ifBlank { "playlist" }
        val dir = java.io.File(context.filesDir, "playlists").apply { mkdirs() }
        val target = java.io.File(dir, if (name.contains('.')) name else "$name.m3u")
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Файл не открывается")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        Uri.fromFile(target).toString()
    }

    suspend fun delete(uri: String) = dao.delete(uri)
    suspend fun setKids(uri: String, kids: Boolean) = dao.setKids(uri, kids)

    /** Files picked through the system picker stay readable after a restart. */
    private fun keepAccess(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not a persistable grant (shared from another app); it still plays this time.
        }
    }

    private fun displayName(uri: Uri, raw: Boolean = false): String? {
        if (uri.scheme != "content") return null
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (raw) it else Links.titleFor(it) } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
