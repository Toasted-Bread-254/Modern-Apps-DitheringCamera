package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun HealingPanel(
    brushSize: Float,
    isSettingSource: Boolean,
    onBrushSizeChanged: (Float) -> Unit,
    onSetSourceToggled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Brush Size", fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = brushSize * 100f,
                onValueChange = { onBrushSizeChanged(it / 100f) },
                valueRange = 1f..15f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(brushSize * 100f).roundToInt()}",
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Set Source Point",
                fontSize = 12.sp,
                color = if (isSettingSource) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = isSettingSource, onCheckedChange = onSetSourceToggled)
        }
    }
}
