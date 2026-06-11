package com.vayunmathur.sdk.games

data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val iconResName: String? = null
)

data class AchievementStatus(
    val achievement: Achievement,
    val unlocked: Boolean,
    val unlockedAt: Long?
)

data class ScoreEntry(
    val gameId: String,
    val category: String,
    val score: Long,
    val timestamp: Long
)

class GameHubNotInstalledException :
    Exception("Games Hub app is not installed on this device")
