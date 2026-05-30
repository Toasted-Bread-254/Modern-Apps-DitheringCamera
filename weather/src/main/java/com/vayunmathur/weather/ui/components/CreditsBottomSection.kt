package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Mirrors WeatherMaster's `CreditsBottomSection`: a single centered,
 * bold-underlined line attributing the data source, with a trailing system
 * insets spacer so the credit sits above the gesture bar.
 */
@Composable
fun CreditsBottomSection() {
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Text(
        text = "Open-Meteo and more",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    androidx.compose.foundation.layout.Spacer(Modifier.height(bottomInset))
}
