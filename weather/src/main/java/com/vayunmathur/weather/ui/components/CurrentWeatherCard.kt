package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.network.Daily
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.weatherConditionForCode

/**
 * Direct port of WeatherMaster's `PixelStyleCurrentWeatherCard`. Centered
 * column: 32 dp condition icon + `titleLarge Medium` condition label, then
 * 136 sp bold `primary`-colored temperature, then `titleLarge Medium`
 * feels-like, then a "Max X° Min Y°" row.
 */
@Composable
fun CurrentWeatherCard(current: Current, today: Daily?, tempUnit: TemperatureUnit) {
    val condition = weatherConditionForCode(current.weatherCode)
    val high = today?.temperatureMax?.firstOrNull()
    val low = today?.temperatureMin?.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIconBox(
                iconRes = condition.iconRes(current.isDay == 1),
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = condition.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = formatTemperatureCompact(current.temperature, tempUnit),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 136.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Feels like ${formatTemperatureCompact(current.apparentTemperature, tempUnit)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        if (high != null && low != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Max ${formatTemperatureCompact(high, tempUnit)}  Min ${formatTemperatureCompact(low, tempUnit)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}
