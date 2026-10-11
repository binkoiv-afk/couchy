package app.couchy.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.couchy.core.Sortable
import kotlinx.coroutines.flow.Flow

/**
 * One thing to watch: a file, a stream or (later) a title from a source. The link is the key, so
 * opening the same video again finds its history and position.
 */
@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val uri: String,
    override val title: String,
    /** [app.couchy.core.LinkKind] name. */
    val kind: String,
    override val addedAt: Long,
    override val lastWatchedAt: Long = 0,
    override val positionMs: Long = 0,
    override val durationMs: Long = 0,
    val favorite: Boolean = false,
    /** Language of the audio track picked last time, reused on the next play. */
    val audioLanguage: String? = null,
    /** Language of the subtitles picked last time; empty string means subtitles were turned off. */
    val subtitleLanguage: String? = null,
    /** A parent allowed it in kids mode. */
    val kids: Boolean = false,
) : Sortable

@Dao
interface MediaDao {
    @Query("SELECT * FROM media")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media")
    suspend fun all(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE uri = :uri")
    suspend fun get(uri: String): MediaEntity?

    @Upsert
    suspend fun upsert(entity: MediaEntity)

    @Query("UPDATE media SET positionMs = :positionMs, durationMs = :durationMs, lastWatchedAt = :at WHERE uri = :uri")
    suspend fun updateProgress(uri: String, positionMs: Long, durationMs: Long, at: Long)

    @Query("UPDATE media SET audioLanguage = :audio, subtitleLanguage = :subtitles WHERE uri = :uri")
    suspend fun updateTracks(uri: String, audio: String?, subtitles: String?)

    @Query("UPDATE media SET favorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query("UPDATE media SET positionMs = 0, lastWatchedAt = 0 WHERE uri = :uri")
    suspend fun clearProgress(uri: String)

    @Query("UPDATE media SET kids = :kids WHERE uri = :uri")
    suspend fun setKids(uri: String, kids: Boolean)

    @Query("DELETE FROM media WHERE uri = :uri")
    suspend fun delete(uri: String)
}

@Database(entities = [MediaEntity::class], version = 2, exportSchema = true)
abstract class CouchyDatabase : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        fun create(context: Context): CouchyDatabase =
            Room.databaseBuilder(context, CouchyDatabase::class.java, "couchy${Profiles.suffix(context)}.db").addMigrations(KIDS).build()

        private val KIDS = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN kids INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
