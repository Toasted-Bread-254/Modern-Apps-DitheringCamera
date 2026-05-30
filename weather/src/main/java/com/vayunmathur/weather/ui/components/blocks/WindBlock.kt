package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.util.WindUnit
import com.vayunmathur.weather.util.compassDirection
import com.vayunmathur.weather.util.formatWind

/**
 * Port of WeatherMaster's `WindBlock`. Circular surface with a big arrow
 * drawable rotated by the wind direction degrees behind the value. Big
 * value + unit centered, "From X" direction bottom-center.
 */
@Composable
fun WindBlock(current: Current, unit: WindUnit) {
    val degrees = current.windDirection.toFloat()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
            Image(
                painter = painterResource(R.drawable.weather_wind_arrow_dominant),
                contentDescription = null,
                modifier = Modifier.matchParentSize().rotate(degrees),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inversePrimary),
            )
            Box(Modifier.align(Alignment.TopCenter)) {
                BlockHeader(
                    iconRes = R.drawable.outline_wind_24,
                    title = "Wind",
                    topPadding = 36.dp,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.Center).offset(y = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatWind(current.windSpeed, unit).substringBefore(' '),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.alignByBaseline(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = formatWind(current.windSpeed, unit).substringAfter(' '),
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "From ${compassDirection(current.windDirection)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .padding(horizontal = 16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
