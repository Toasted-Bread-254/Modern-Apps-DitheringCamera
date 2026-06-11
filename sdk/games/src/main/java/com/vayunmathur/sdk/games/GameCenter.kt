package com.vayunmathur.sdk.games

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GameCenter(private val context: Context, private val gameId: String) {

    companion object {
        private const val AUTHORITY = "com.vayunmathur.games.provider"
        private const val HUB_PACKAGE = "com.vayunmathur.games"
        private val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")
    }

    suspend fun registerAchievements(achievements: List<Achievement>) = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(BASE_URI, "achievements/$gameId")
        for (a in achievements) {
            val values = ContentValues().apply {
                put("achievement_id", a.id)
                put("game_id", gameId)
                put("name", a.name)
                put("description", a.description)
                put("icon_res_name", a.iconResName)
                put("unlocked", 0)
            }
            try {
                resolver.insert(uri, values)
            } catch (_: Exception) {
                // already registered or hub unavailable
            }
        }
    }

    suspend fun unlockAchievement(achievementId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(BASE_URI, "achievements/$gameId/$achievementId")
        val values = ContentValues().apply {
            put("unlocked", 1)
            put("unlocked_at", System.currentTimeMillis())
        }
        try {
            resolver.update(uri, values, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getAchievements(): List<AchievementStatus> = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext emptyList()
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(BASE_URI, "achievements/$gameId")
        val results = mutableListOf<AchievementStatus>()
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val achievement = Achievement(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("achievement_id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        iconResName = cursor.getString(cursor.getColumnIndexOrThrow("icon_res_name"))
                    )
                    val unlocked = cursor.getInt(cursor.getColumnIndexOrThrow("unlocked")) == 1
                    val unlockedAt = if (unlocked) {
                        cursor.getLong(cursor.getColumnIndexOrThrow("unlocked_at"))
                    } else null
                    results.add(AchievementStatus(achievement, unlocked, unlockedAt))
                }
            }
        } catch (_: Exception) { }
        results
    }

    suspend fun reportScore(category: String, score: Long) = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(BASE_URI, "scores/$gameId")
        val values = ContentValues().apply {
            put("game_id", gameId)
            put("category", category)
            put("score", score)
            put("timestamp", System.currentTimeMillis())
        }
        try {
            resolver.insert(uri, values)
        } catch (_: Exception) { }
    }

    suspend fun getScores(category: String): List<ScoreEntry> = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext emptyList()
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(BASE_URI, "scores/$gameId")
        val results = mutableListOf<ScoreEntry>()
        try {
            resolver.query(uri, null, "category = ?", arrayOf(category), "score DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(
                        ScoreEntry(
                            gameId = cursor.getString(cursor.getColumnIndexOrThrow("game_id")),
                            category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                            score = cursor.getLong(cursor.getColumnIndexOrThrow("score")),
                            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        results
    }

    fun isHubInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(HUB_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
