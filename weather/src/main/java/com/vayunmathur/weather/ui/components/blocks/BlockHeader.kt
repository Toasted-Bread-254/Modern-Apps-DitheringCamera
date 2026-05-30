package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Header row used inside every block (icon + bold title), mirroring
 * WeatherMaster's private `Header()` per-block helper. Default top padding
 * is 16.dp; visibility/pressure/wind blocks pass a larger top offset
 * because they use circular surfaces where the header floats inside the
 * curve.
 */
@Composable
fun BlockHeader(
    iconRes: Int,
    title: String,
    topPadding: Dp = 16.dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = topPadding, start = 12.dp, end = 12.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
    }
}
