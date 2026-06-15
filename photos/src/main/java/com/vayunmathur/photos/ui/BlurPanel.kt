package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.BlurMode
import com.vayunmathur.photos.data.BlurParams
import kotlin.math.roundToInt

@Composable
fun BlurPanel(
    blurParams: BlurParams,
    onBlurChanged: (BlurParams) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BlurMode.entries.forEach { mode ->
                FilterChip(
                    selected = blurParams.mode == mode,
                    onClick = { onBlurChanged(blurParams.copy(mode = mode)) },
                    label = { Text(mode.name, fontSize = 12.sp) },
                )
            }
        }

        BlurSlider("Intensity", blurParams.intensity, 0f, 50f) {
            onBlurChanged(blurParams.copy(intensity = it))
        }
        BlurSlider("Feather", blurParams.feather * 100f, 0f, 100f) {
            onBlurChanged(blurParams.copy(feather = it / 100f))
        }
        BlurSlider("Radius", blurParams.radius * 100f, 5f, 80f) {
            onBlurChanged(blurParams.copy(radius = it / 100f))
        }
    }
}

@Composable
private fun BlurSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${value.roundToInt()}",
            fontSize = 12.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
