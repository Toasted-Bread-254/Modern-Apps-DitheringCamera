package com.vayunmathur.openassistant.data
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity
data class Conversation(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

/**
 * Data class representing a persistent Message within a conversation.
 */
@Entity
data class Message(
    val conversationId: Long,
    val text: String,
    val role: String,
    val imagePaths: List<String> = emptyList(), // Store as paths for better persistence
    val hasAudio: Boolean = false,
    val missingAppPackage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem

@Entity
data class Memory(
    val content: String,
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
): DatabaseItem


@Dao
interface ConversationDao: TrueDao<Conversation>

@Dao
interface MessageDao: TrueDao<Message>

@Dao
interface MemoryDao: TrueDao<Memory>

@TypeConverters(DefaultConverters::class)
@Database(entities = [Conversation::class, Message::class, Memory::class], version = 3)
abstract class AppDatabase: RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE Message ADD COLUMN missingAppPackage TEXT")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `Memory` (`content` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                }
            }
        )
    }
}
