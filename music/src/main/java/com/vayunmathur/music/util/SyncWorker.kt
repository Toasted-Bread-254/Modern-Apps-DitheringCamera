package com.vayunmathur.music.util
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.getAll
import com.vayunmathur.music.data.MIGRATION_1_2
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.MIGRATION_2_3
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult = withContext(Dispatchers.IO) {
        val database = applicationContext.buildDatabase<MusicDatabase>()
        val viewModel = DatabaseViewModel(
            database,
            Music::class to database.musicDao(),
            Album::class to database.albumDao(),
            Artist::class to database.artistDao(),
            Playlist::class to database.playlistDao(),
            matchingDao = database.matchingDao()
        )
        
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        val lastGeneration = dataStore.getLong("last_music_generation") ?: 0L
        val currentGeneration = MediaStore.getGeneration(applicationContext, MediaStore.VOLUME_EXTERNAL)

        val triggeredUris = triggeredContentUris
        if (triggeredUris.isNotEmpty()) {
            syncMusic(applicationContext, database, viewModel, triggeredUris.toList())
        } else {
            syncMusic(applicationContext, database, viewModel, null, lastGeneration)
        }

        dataStore.setLong("last_music_generation", currentGeneration)
        
        // Enqueue next observation
        enqueue(applicationContext)
        
        WorkResult.success()
    }

    companion object {
        private const val WORK_NAME = "MusicSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

suspend fun syncMusic(context: Context, database: MusicDatabase, viewModel: DatabaseViewModel, uris: List<Uri>? = null, lastGeneration: Long = 0L) {
    val musicDao = database.musicDao()

    // 1. Get all current IDs in MediaStore to handle deletions correctly
    val allMediaStoreIds = mutableSetOf<Long>()
    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                try {
                    allMediaStoreIds.add(cursor.getLong(idCol))
                } catch (e: Exception) {
                    Log.e("MusicSyncWorker", "Error reading music ID from cursor", e)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("MusicSyncWorker", "Error querying MediaStore for music IDs", e)
    }

    // 2. Handle deletions
    val toDelete = if (uris != null) {
        val triggeredIds = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }.toSet()
        triggeredIds - allMediaStoreIds
    } else {
        val localIds = musicDao.getAll<Music>().map { it.id }.toSet()
        localIds - allMediaStoreIds
    }

    if (toDelete.isNotEmpty()) {
        toDelete.chunked(900).forEach { chunk ->
            musicDao.observeNothing(SimpleSQLiteQuery("DELETE FROM Music WHERE id IN (${chunk.joinToString(",")})"))
        }
    }

    // 3. Process incremental or full updates for Music
    val musicList = mutableListOf<Music>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK
    )

    val selection = when {
        uris != null -> {
            val ids = uris.mapNotNull { runCatching { ContentUris.parseId(it) }.getOrNull() }
            if (ids.isEmpty()) "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            else "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")})"
        }
        lastGeneration > 0 -> {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > $lastGeneration"
        }
        else -> "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    }

    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val artistIDColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val albumIDColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    val artist = cursor.getString(artistColumn)
                    val album = cursor.getString(albumColumn)
                    val artistID = cursor.getLong(artistIDColumn)
                    val albumID = cursor.getLong(albumIDColumn)
                    val contentUriObject = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    var duration = cursor.getLong(durationColumn)

                    if (duration == 0L) {
                        duration = getRealAudioDuration(context, contentUriObject)
                    }

                    val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                    val rawTrack = cursor.getInt(trackColumn)
                    val trackNumber = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack
                    val year = getAudioYear(context, contentUriObject)

                    musicList.add(Music(id, title, artist, artistID, album, albumID, contentUri, duration, trackNumber, year))
                } catch (e: Exception) {
                    Log.e("MusicSyncWorker", "Error constructing music from cursor", e)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("MusicSyncWorker", "Error querying MediaStore for music", e)
    }

    if (uris == null && lastGeneration == 0L) {
        viewModel.replaceAll(musicList)
    } else {
        musicDao.upsertAll(musicList)
    }

    // 4. Always refresh Albums and Artists completely to ensure consistency
    val albums = getAlbums(context)
    val artists = getArtists(context)
    
    viewModel.replaceAll(albums)
    viewModel.replaceAll(artists)
    
    // 5. Rebuild relationship matchings
    val allMusic = musicDao.getAll<Music>()
    val allAlbums = database.albumDao().getAll<Album>()
    val allArtists = database.artistDao().getAll<Artist>()
    
    Log.d("MusicSyncWorker", "Rebuilding matchings: Music=${allMusic.size}, Albums=${allAlbums.size}, Artists=${allArtists.size}")
    
    val pairs = albumArtistPairs(allMusic, allArtists, allAlbums)
    Log.d("MusicSyncWorker", "Rebuilding matchings: ${pairs.size} pairs found")
    
    viewModel.clearMatchings<Album, Artist>()
    viewModel.addPairs(pairs)
}
