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
import com.vayunmathur.weather.util.PressureUnit

/**
 * Port of WeatherMaster's `PressureBlock`. Circular surface with two
 * stacked Images: the full progress container ring (tinted
 * `surfaceContainerHigh`) and the active arc (tinted `primary`) chosen by
 * the hPa bucket. Big inHg value centered, unit bottom-center.
 */
@Composable
fun PressureBlock(current: Current, pressureUnit: PressureUnit) {
    val valueText: String
    val unitText: String
    when (pressureUnit) {
        PressureUnit.InHg -> {
            val inHg = current.pressureMsl * 0.02953
            valueText = String.format("%.2f", inHg)
            unitText = "inHg"
        }
        PressureUnit.Hpa -> {
            valueText = current.pressureMsl.toInt().toString()
            unitText = "hPa"
        }
    }
    val progressDrawable = when (pressureUnit) {
        PressureUnit.InHg -> {
            val inHg = current.pressureMsl * 0.02953
            when {
                inHg < 29.0 -> R.drawable.pressure_progress_low
                inHg in 29.0..29.7 -> R.drawable.pressure_progress_medium
                inHg in 29.7..30.2 -> R.drawable.pressure_progress_low_medium
                inHg in 30.2..31.0 -> R.drawable.pressure_progress_high
                else -> R.drawable.pressure_progress_very_high
            }
        }
        PressureUnit.Hpa -> {
            val pressureHpa = current.pressureMsl.toInt()
            when {
                pressureHpa < 980 -> R.drawable.pressure_progress_low
                pressureHpa in 980..1005 -> R.drawable.pressure_progress_medium
                pressureHpa in 1005..1020 -> R.drawable.pressure_progress_low_medium
                pressureHpa in 1020..1035 -> R.drawable.pressure_progress_high
                else -> R.drawable.pressure_progress_very_high
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().aspectRatio(1f)) {
            Image(
                painter = painterResource(R.drawable.pressure_progress_container),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Image(
                painter = painterResource(progressDrawable),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
            Box(Modifier.align(Alignment.TopCenter)) {
                BlockHeader(
                    iconRes = R.drawable.outline_pressure_24,
                    title = "Pressure",
                    topPadding = 38.dp,
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.Center).offset(y = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unitText,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-24).dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
