package com.vayunmathur.weather.ui.components

import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable weather-icon renderer. Same role as WeatherMaster's
 * `WeatherIconBox` — caller provides the drawable id, we render it at the
 * requested size in `onSurface` color (overridable).
 */
@Composable
fun WeatherIconBox(
    iconRes: Int,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint,
    )
}
