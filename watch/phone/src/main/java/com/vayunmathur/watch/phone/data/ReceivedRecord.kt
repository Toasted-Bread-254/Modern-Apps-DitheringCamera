package com.vayunmathur.watch.phone.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Phone-side rolling buffer of raw records received from the watch. The watch
 * deletes rows after the phone ACKs, so the phone keeps its own copy here to
 * recompute daily/overnight derivations as more data streams in.
 */
@Entity
data class ReceivedRecord(
    // Composite of the watch row id and its type: watch ids restart on reinstall,
    // so pairing with type keeps the buffer key stable and de-duplicated.
    @PrimaryKey val key: String,
    val type: String,
    val timestamp: Long,
    val value: Double,
    val delta: Double,
    val stationary: Boolean,
)

@Dao
interface ReceivedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<ReceivedRecord>)

    @Query(
        "SELECT * FROM ReceivedRecord WHERE type = :type AND timestamp >= :startMs " +
            "AND timestamp < :endMs ORDER BY timestamp ASC",
    )
    suspend fun getByTypeInRange(type: String, startMs: Long, endMs: Long): List<ReceivedRecord>

    @Query("SELECT * FROM ReceivedRecord WHERE type = :type ORDER BY timestamp ASC")
    suspend fun getByType(type: String): List<ReceivedRecord>

    @Query(
        "SELECT * FROM ReceivedRecord WHERE timestamp >= :startMs AND timestamp < :endMs " +
            "ORDER BY timestamp ASC",
    )
    suspend fun getInRange(startMs: Long, endMs: Long): List<ReceivedRecord>

    @Query("SELECT MIN(timestamp) FROM ReceivedRecord")
    suspend fun earliestTimestamp(): Long?

    @Query("DELETE FROM ReceivedRecord WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

@Database(entities = [ReceivedRecord::class], version = 1, exportSchema = false)
abstract class ReceivedDatabase : RoomDatabase() {
    abstract fun receivedDao(): ReceivedDao

    companion object {
        @Volatile
        private var INSTANCE: ReceivedDatabase? = null

        fun get(context: Context): ReceivedDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReceivedDatabase::class.java,
                    "received.db",
                ).build().also { INSTANCE = it }
            }
    }
}
