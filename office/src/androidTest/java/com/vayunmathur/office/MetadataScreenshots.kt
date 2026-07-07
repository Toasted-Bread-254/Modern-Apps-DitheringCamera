package com.vayunmathur.office

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:office:metadata`. Opens the three real sample documents that
 * ship in `metadata_data/assets` (bundled into this test APK's assets) exactly the way a user
 * would: each file is staged onto the device and handed to [MainActivity] through an
 * `ACTION_VIEW` intent, so the store shots show the app rendering genuine documents.
 *
 * Order (best first): doc = 1, spreadsheet = 2, presentation = 3.
 *
 * The shared [com.vayunmathur.metadata.PlaystoreIconRenderer] test (from build-logic) runs in the
 * same instrumentation pass and is untouched.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    // We launch each activity ourselves via ActivityScenario, so the compose rule only needs to
    // attach to whatever composition is currently on screen.
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // The instrumentation (test-apk) context owns the bundled androidTest assets.
    private val testCtx = instrumentation.context

    // The app-under-test context: instrumentation loads us into its process, so MediaStore entries
    // we insert here are owned by the app and readable back without any cross-app uri grant.
    private val appCtx = instrumentation.targetContext

    private val outDir: File by lazy {
        File(appCtx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private data class Shot(val index: Int, val asset: String, val mime: String)

    private val shots = listOf(
        Shot(1, "file-sample_1MB.docx", MIME_DOCX),
        Shot(2, "file_example_XLSX_5000.xlsx", MIME_XLSX),
        Shot(3, "Dickinson_Sample_Slides.pptx", MIME_PPTX),
    )

    @Test
    fun generateStoreScreenshots() {
        for (shot in shots) {
            val uri = stageAsset(shot.asset, shot.mime)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(appCtx.packageName, "com.vayunmathur.office.MainActivity")
                setDataAndType(uri, shot.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                waitForDocument()
                snap(shot.index)
            }
        }
    }

    /** Waits for the editor to finish loading (the loading spinner to disappear), then settles. */
    private fun waitForDocument() {
        // Let the first composition (which shows the loading spinner) come up before we poll.
        Thread.sleep(800)
        runCatching {
            composeRule.waitUntil(timeoutMillis = 40_000) {
                composeRule
                    .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                    .fetchSemanticsNodes().isEmpty()
            }
        }
        // Give the rendered "paper" a moment to lay out its first full frame.
        Thread.sleep(1500)
        composeRule.waitForIdle()
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Copies a bundled sample document into MediaStore Downloads and returns its content Uri. */
    private fun stageAsset(name: String, mime: String): Uri {
        val resolver = appCtx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/office_metadata")
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: error("Could not create MediaStore entry for $name")
        resolver.openOutputStream(uri)?.use { out ->
            testCtx.assets.open(name).use { input -> input.copyTo(out) }
        } ?: error("Could not open output stream for $name")
        return uri
    }

    private companion object {
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
