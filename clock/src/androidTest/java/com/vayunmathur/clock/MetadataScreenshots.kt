package com.vayunmathur.clock

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not an assertion test: this is a screenshot generator driven by the `:clock:metadata`
 * Gradle task. It seeds the app with realistic sample data (alarms, world clocks, a
 * running stopwatch with laps, a timer), walks the four bottom-nav tabs, and writes one
 * PNG per screen into the app's external files dir, which the task then pulls off device.
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

    // Weekday bitmask helpers (bit 0 = Sunday ... bit 6 = Saturday).
    private val weekdays = 0b0111110 // Mon–Fri
    private val everyDay = 0b1111111
    private val weekend = 0b1000001 // Sun + Sat

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image: ImageBitmap = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun tab(label: String) {
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun ui(block: () -> Unit) = composeRule.runOnUiThread(block)

    @Test
    fun generateStoreScreenshots() {
        val vm = composeRule.runOnUiThread<ClockViewModel> {
            ViewModelProvider(composeRule.activity)[ClockViewModel::class.java]
        }
        val ds = DataStoreUtils.getInstance(ctx)

        // --- Seed sample data ---------------------------------------------------
        ui {
            vm.upsert(Alarm(time = LocalTime(6, 30), name = "Wake up", enabled = true, days = weekdays))
            vm.upsert(Alarm(time = LocalTime(8, 0), name = "Gym", enabled = true, days = everyDay))
            vm.upsert(Alarm(time = LocalTime(9, 30), name = "Weekend lie-in", enabled = false, days = weekend))
        }
        ds.addStringToSet("time_zones", "London")
        ds.addStringToSet("time_zones", "Paris")
        ds.addStringToSet("time_zones", "Sydney")

        // --- 1. Alarm (populated, one card expanded) ---------------------------
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Wake up").fetchSemanticsNodes().isNotEmpty()
        }
        // Expand the options dropdown on the first alarm card.
        composeRule.onAllNodesWithContentDescription("Chevron")[0].performClick()
        snap(1)

        // --- 2. Clock (with world clocks) --------------------------------------
        tab(ctx.getString(R.string.label_clock))
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("London").fetchSemanticsNodes().isNotEmpty()
        }
        snap(2)

        // --- 3. Timer (10:00 punched into the keypad) --------------------------
        tab(ctx.getString(R.string.label_timer))
        listOf("1", "0", "0", "0").forEach { digit ->
            composeRule.onNodeWithText(digit).performClick()
        }
        snap(3)

        // --- 4. Stopwatch (running, with laps) ---------------------------------
        tab(ctx.getString(R.string.label_stopwatch))
        ui { vm.toggleStopwatch() }
        Thread.sleep(2500); ui { vm.addLap() }
        Thread.sleep(2500); ui { vm.addLap() }
        Thread.sleep(1500)
        // Pause so the display/laps are static for a clean capture.
        ui { vm.toggleStopwatch() }
        snap(4)
    }
}
