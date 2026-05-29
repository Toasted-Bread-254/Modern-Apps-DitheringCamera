package com.vayunmathur.notes.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao : TrueDao<Note> {
    @Query("SELECT * FROM Note")
    fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Upsert
    override suspend fun upsert(value: Note): Long

    @Delete
    override suspend fun delete(value: Note): Int

    @Upsert
    override suspend fun upsertAll(t: List<Note>)
}

@Database(entities = [Note::class], version = 1)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}
