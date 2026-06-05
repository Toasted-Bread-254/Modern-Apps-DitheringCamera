package com.vayunmathur.games.pipes.util

import android.content.Context
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.library.util.AchievementsManager

class PipesAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) {
            onAchievementUnlocked("first_flow")
            onAchievementUnlocked("first_level")
        }
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("pipes_1000", repository.getTotalPipesPlaced())
    }
}
