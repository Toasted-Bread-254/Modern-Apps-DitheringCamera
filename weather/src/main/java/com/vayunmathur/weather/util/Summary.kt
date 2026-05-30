package com.vayunmathur.weather.util

import com.vayunmathur.weather.network.ForecastResponse

/**
 * Rule-based one-paragraph human summary of today's forecast. Same role as
 * WeatherMaster's `computeDaySummary`, but synthesized purely from the
 * data we already have — no LLM, no extra network calls. Deterministic so
 * the same input always produces the same string.
 */
fun computeDaySummary(forecast: ForecastResponse, tempUnit: TemperatureUnit): String {
    val current = forecast.current
    val daily = forecast.daily
    val conditionLabel = current?.weatherCode?.let { weatherConditionForCode(it).label.lowercase() }
        ?: "mixed conditions"
    val hi = daily?.temperatureMax?.firstOrNull()
    val lo = daily?.temperatureMin?.firstOrNull()
    val precip = daily?.precipitationProbabilityMax?.firstOrNull() ?: 0
    val wind = current?.windSpeed

    val parts = mutableListOf<String>()
    parts.add("Expect $conditionLabel throughout the day.")
    if (hi != null && lo != null) {
        parts.add("High ${formatTemperatureCompact(hi, tempUnit)}, low ${formatTemperatureCompact(lo, tempUnit)}.")
    }
    when {
        precip >= 70 -> parts.add("Rain is likely — bring an umbrella.")
        precip >= 40 -> parts.add("Showers are possible later on.")
        precip >= 20 -> parts.add("A slight chance of rain.")
    }
    if (wind != null && wind >= 30) {
        parts.add("Winds will be noticeable today.")
    }
    return parts.joinToString(" ")
}
