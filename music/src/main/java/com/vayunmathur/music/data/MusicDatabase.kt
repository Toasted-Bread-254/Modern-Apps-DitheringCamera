package com.vayunmathur.music.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import com.vayunmathur.library.util.TrueDao
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao : TrueDao<Music> {
    @Query("SELECT * FROM Music")
    fun getAllFlow(): Flow<List<Music>>
    @Query("SELECT * FROM Music WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Music?>
}

@Dao
interface AlbumDao : TrueDao<Album> {
    @Query("SELECT * FROM Album")
    fun getAllFlow(): Flow<List<Album>>
    @Query("SELECT * FROM Album WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Album?>
}

@Dao
interface ArtistDao : TrueDao<Artist> {
    @Query("SELECT * FROM Artist")
    fun getAllFlow(): Flow<List<Artist>>
    @Query("SELECT * FROM Artist WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Artist?>
}

@Dao
interface PlaylistDao : TrueDao<Playlist> {
    @Query("SELECT * FROM Playlist")
    fun getAllFlow(): Flow<List<Playlist>>
    @Query("SELECT * FROM Playlist WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Playlist?>
}

@TypeConverters(DefaultConverters::class)
@Database(entities = [Music::class, Album::class, Artist::class, Playlist::class, ManyManyMatching::class], version = 3)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun matchingDao(): MatchingDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Many-to-many matching type codes. These mirror the indices used by the
// pre-refactor `DatabaseViewModel(db, Music::class to ..., Album::class to ...,
// Artist::class to ..., Playlist::class to ...)` registration: type code is
// `min(a,b) + 100*max(a,b)` over the indices (Music=0, Album=1, Artist=2,
// Playlist=3). The "left" side of a row in `ManyManyMatching` is the entity
// with the smaller index.
// ─────────────────────────────────────────────────────────────────────────────
const val TYPE_MUSIC_ALBUM: Int = 0 + 100 * 1       // 100, left=Music,  right=Album
const val TYPE_MUSIC_ARTIST: Int = 0 + 100 * 2      // 200, left=Music,  right=Artist
const val TYPE_MUSIC_PLAYLIST: Int = 0 + 100 * 3    // 300, left=Music,  right=Playlist
const val TYPE_ALBUM_ARTIST: Int = 1 + 100 * 2      // 201, left=Album,  right=Artist
const val TYPE_ALBUM_PLAYLIST: Int = 1 + 100 * 3    // 301, left=Album,  right=Playlist
const val TYPE_ARTIST_PLAYLIST: Int = 2 + 100 * 3   // 302, left=Artist, right=Playlist

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `Playlist` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
            `name` TEXT NOT NULL
        )
        """.trimIndent()
    )
}

val MIGRATION_2_3 = Migration(2, 3) {
    it.execSQL("ALTER TABLE Music ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
    it.execSQL("ALTER TABLE Music ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
    it.execSQL("ALTER TABLE Music ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
}
