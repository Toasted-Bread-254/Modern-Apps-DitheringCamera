package com.vayunmathur.photos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.DynamicTheme

/**
 * Lightweight full-screen viewer for an `ACTION_VIEW` image/video URI handed
 * to us by another app (e.g. the camera's preview "open with"). It renders
 * the supplied URI directly rather than going through the gallery's media
 * database, so it works even for items that haven't been indexed yet.
 */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val mimeType = intent.type ?: contentResolver.getType(uri).orEmpty()
        val isVideo = mimeType.startsWith("video/")

        setContent {
            DynamicTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isVideo) {
                        VideoPlayer(
                            modifier = Modifier.fillMaxSize(),
                            uri = uri,
                            isMetadataVisible = true,
                            isSettledPage = true,
                        )
                    } else {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}
