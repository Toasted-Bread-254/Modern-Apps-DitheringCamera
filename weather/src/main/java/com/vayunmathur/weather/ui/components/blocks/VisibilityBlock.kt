package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import kotlin.math.roundToInt

/**
 * Port of WeatherMaster's `VisibilityBlock`. Circular surface with the
 * inner cookie-shape drawable as a decorative background tinted with
 * `inversePrimary`. Header top-center, big value centered, unit
 * bottom-center.
 */
@Composable
fun VisibilityBlock(current: Current, useMiles: Boolean = false) {
    val value: Int
    val unit: String
    if (useMiles) {
        value = (current.visibility / 1609.34).roundToInt()
        unit = "mi"
    } else {
        value = (current.visibility / 1000).roundToInt()
        unit = "km"
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
            Image(
                painter = painterResource(R.drawable.visibility_block),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inversePrimary),
            )
            Box(Modifier.align(Alignment.TopCenter)) {
                BlockHeader(
                    iconRes = R.drawable.outline_visibility_24,
                    title = "Visibility",
                    topPadding = 36.dp,
                )
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.Center).offset(y = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unit,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-30).dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
