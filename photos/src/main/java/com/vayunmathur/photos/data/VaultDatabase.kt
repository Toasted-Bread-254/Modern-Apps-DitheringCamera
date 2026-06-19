package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class VaultPhoto(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val path: String, // Path in internal storage
    val thumbnailPath: String, // Path to encrypted thumbnail
    val date: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val videoDuration: Long? = null,
) : DatabaseItem

@Dao
interface VaultPhotoDao {
    @Query("SELECT * FROM VaultPhoto")
    fun getAllFlow(): Flow<List<VaultPhoto>>

    @Query("SELECT * FROM VaultPhoto WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<VaultPhoto?>

    @Upsert
    suspend fun upsert(value: VaultPhoto): Long

    @Delete
    suspend fun delete(value: VaultPhoto): Int
}

@Database(entities = [VaultPhoto::class], version = 1, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultPhotoDao(): VaultPhotoDao
}
