package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PerspectivePanel(
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        Text(
            "Drag the corner handles to adjust perspective",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Surface(
                modifier = Modifier.clickable { onReset() },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    "Reset",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    "Apply",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
