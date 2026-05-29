package com.vayunmathur.music.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import com.vayunmathur.library.util.TrueDao
import androidx.room.migration.Migration

@Dao
interface MusicDao: TrueDao<Music>
@Dao
interface AlbumDao: TrueDao<Album>
@Dao
interface ArtistDao: TrueDao<Artist>
@Dao
interface PlaylistDao: TrueDao<Playlist>

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