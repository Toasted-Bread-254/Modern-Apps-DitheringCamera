package com.vayunmathur.games.unblockjam.util

import android.content.Context
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.library.util.AchievementsManager

class UnblockJamAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) onAchievementUnlocked("first_level")
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("moves_1000", repository.getTotalMoves())
        onProgressUpdated("undo_master", repository.getUndoCount())
        onProgressUpdated("all_levels_pack_0", stats.size)
    }
}
