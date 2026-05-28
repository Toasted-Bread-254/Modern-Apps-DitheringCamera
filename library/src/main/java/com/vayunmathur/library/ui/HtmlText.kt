package com.vayunmathur.library.ui

import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val isDark = isSystemInDarkTheme()
    
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    defaultFontSize = 14
                }
                setBackgroundColor(backgroundColor)
            }
        },
        update = { webView ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(
                    webView.settings,
                    if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
