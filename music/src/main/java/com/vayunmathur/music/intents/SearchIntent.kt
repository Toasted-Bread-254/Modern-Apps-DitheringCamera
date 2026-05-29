package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.getAll
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class SearchIntent: AssistantIntent<String, List<MusicSearchResult>>(serializer<String>(), serializer<List<MusicSearchResult>>()) {

    override suspend fun performCalculation(input: String): List<MusicSearchResult> {
        val db = buildDatabase<MusicDatabase>()

        val results = mutableListOf<MusicSearchResult>()

        db.musicDao().getAll<Music>().filter { it.title.contains(input, ignoreCase = true) }.forEach {
            results.add(MusicSearchResult(it.id, it.title, "song"))
        }

        db.albumDao().getAll<Album>().filter { it.name.contains(input, ignoreCase = true) }.forEach {
            results.add(MusicSearchResult(it.id, it.name, "album"))
        }

        db.artistDao().getAll<Artist>().filter { it.name.contains(input, ignoreCase = true) }.forEach {
            results.add(MusicSearchResult(it.id, it.name, "artist"))
        }

        db.playlistDao().getAll<Playlist>().filter { it.name.contains(input, ignoreCase = true) }.forEach {
            results.add(MusicSearchResult(it.id, it.name, "playlist"))
        }

        return results
    }
}
