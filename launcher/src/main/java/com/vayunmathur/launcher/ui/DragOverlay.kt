package com.vayunmathur.launcher.ui

import android.graphics.drawable.Drawable
import com.vayunmathur.launcher.util.toImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun DragOverlay(
    icon: Drawable,
    offset: Offset,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val bitmap = remember(icon) { icon.toImageBitmap() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .offset(
                    x = with(density) { (offset.x - 32.dp.toPx()).toDp() },
                    y = with(density) { (offset.y - 32.dp.toPx()).toDp() }
                ),
            alpha = 0.9f
        )
    }
}
