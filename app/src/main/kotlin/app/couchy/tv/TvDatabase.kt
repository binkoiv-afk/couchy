package app.couchy.tv

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import app.couchy.core.iptv.Catchup
import kotlinx.coroutines.flow.Flow

enum class PlaylistKind { M3U, XTREAM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    /** M3U address; for Xtream, the server. */
    val url: String,
    val username: String? = null,
    val password: String? = null,
    /** Guide address the user typed; overrides the one in the playlist header. */
    val epgOverride: String? = null,
    /** Guide addresses found in the playlist, comma separated. */
    val epgDetected: String? = null,
    val updatedAt: Long = 0,
    val epgUpdatedAt: Long = 0,
    val channelCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "channels", indices = [Index("playlistId"), Index("epgId")])
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    /** The guide channel this one was matched to, if any. */
    val epgId: String?,
    val catchupType: String?,
    val catchupSource: String?,
    val catchupDays: Int,
    val userAgent: String?,
    val referrer: String?,
    val favorite: Boolean = false,
) {
    val catchup: Catchup?
        get() = catchupType?.let { t -> runCatching { Catchup(Catchup.Type.valueOf(t), catchupSource, catchupDays) }.getOrNull() }

    val headers: Map<String, String>
        get() = buildMap {
            userAgent?.let { put("User-Agent", it) }
            referrer?.let { put("Referer", it) }
        }
}

@Entity(tableName = "programs", indices = [Index(value = ["epgId", "start"])])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

@Dao
interface TvDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: Long): PlaylistEntity?

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Update
    suspend fun updatePlaylist(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("SELECT * FROM channels ORDER BY playlistId, position")
    fun observeChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY position")
    suspend fun channels(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun channel(id: Long): ChannelEntity?

    @Insert
    suspend fun insertChannels(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannels(playlistId: Long)

    @Query("UPDATE channels SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    suspend fun setEpgId(id: Long, epgId: String?)

    @Transaction
    suspend fun replaceChannels(playlistId: Long, list: List<ChannelEntity>) {
        deleteChannels(playlistId)
        insertChannels(list)
    }

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        deleteChannels(id)
        deletePlaylistRow(id)
    }

    /** What is on now and next, for every channel with a guide. */
    @Query("SELECT * FROM programs WHERE stop > :now AND start < :until ORDER BY epgId, start")
    fun observeUpcoming(now: Long, until: Long): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM programs WHERE epgId = :epgId AND stop > :from AND start < :to ORDER BY start")
    fun observeSchedule(epgId: String, from: Long, to: Long): Flow<List<ProgramEntity>>

    @Insert
    suspend fun insertPrograms(list: List<ProgramEntity>)

    @Query("DELETE FROM programs WHERE epgId IN (:ids)")
    suspend fun deletePrograms(ids: List<String>)

    @Query("DELETE FROM programs WHERE stop < :before")
    suspend fun deleteOldPrograms(before: Long)
}

/** IPTV lives in its own database so the media library never needs a migration for it. */
@Database(entities = [PlaylistEntity::class, ChannelEntity::class, ProgramEntity::class], version = 1, exportSchema = true)
abstract class TvDatabase : RoomDatabase() {
    abstract fun tv(): TvDao

    companion object {
        fun create(context: Context): TvDatabase =
            Room.databaseBuilder(context, TvDatabase::class.java, "tv.db").build()
    }
}
