package com.vayunmathur.music

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.util.syncMusic
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Screenshot generator driven by `:music:metadata`. Seeds a few short WAV tracks (with
 * title/artist/album metadata) into MediaStore, indexes them, then captures the library.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** A valid 1-second silent mono 16-bit PCM WAV so MediaStore treats it as playable audio. */
    private fun silentWav(): ByteArray {
        val sampleRate = 8000
        val numSamples = sampleRate // 1 second
        val dataSize = numSamples * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataSize); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataSize)
        // data left as zeros (silence)
        return buf.array()
    }

    private fun seedMusic() {
        val tracks = listOf(
            Track("Midnight Drive", "The Neon Owls", "After Hours"),
            Track("Golden Hour", "The Neon Owls", "After Hours"),
            Track("Paper Planes", "Marina Vale", "Coastlines"),
            Track("Coastlines", "Marina Vale", "Coastlines"),
            Track("Slow Mornings", "Kite & Ember", "Homebound"),
            Track("City Lights", "Kite & Ember", "Homebound"),
            Track("Wildflower", "June Sparrow", "Meadow"),
            Track("Riptide Blue", "June Sparrow", "Meadow"),
        )
        val resolver = ctx.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val wav = silentWav()
        tracks.forEachIndexed { i, t ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "${t.title}.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Metadata")
                put(MediaStore.Audio.Media.TITLE, t.title)
                put(MediaStore.Audio.Media.ARTIST, t.artist)
                put(MediaStore.Audio.Media.ALBUM, t.album)
                put(MediaStore.Audio.Media.TRACK, i + 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@forEachIndexed
            resolver.openOutputStream(uri)!!.use { it.write(wav) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private data class Track(val title: String, val artist: String, val album: String)

    @Test
    fun generateStoreScreenshots() {
        seedMusic()
        // Index MediaStore into the app's Room DB directly (shares the cached instance
        // the app uses), so the library populates deterministically.
        val db = ctx.buildDatabase<MusicDatabase>()
        runBlocking { syncMusic(ctx, db) }

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(4000)
            snap(1)
        }
    }
}
