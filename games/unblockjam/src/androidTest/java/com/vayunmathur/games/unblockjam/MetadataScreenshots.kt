package com.vayunmathur.games.unblockjam

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.games.unblockjam.data.LevelPack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:unblockjam:metadata`. Captures a single shot:
 * the gameplay board.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        LevelPack.init(ctx)
        val firstPack = LevelPack.PACKS[0].name

        // Navigate pack selector -> level grid -> first level, and capture only the board.
        composeRule.onNodeWithText(firstPack).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("1").performClick()
        snap(1) // gameplay board
    }
}
