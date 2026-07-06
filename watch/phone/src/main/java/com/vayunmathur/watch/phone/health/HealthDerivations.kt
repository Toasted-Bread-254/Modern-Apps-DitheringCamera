package com.vayunmathur.watch.phone.health

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.vayunmathur.watch.phone.data.ReceivedRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.pow

/**
 * Pure, side-effect-free derivations run over the phone-side record buffer.
 *
 * Everything here is heuristic/approximate by design: the watch only streams raw
 * HR, steps, barometric pressure and coarse motion state, so resting HR, active
 * calories, floors/elevation and sleep are all estimates. All outputs carry a
 * day-keyed clientRecordId so recomputing on later syncs upserts instead of
 * duplicating in Health Connect.
 */
class HealthDerivations(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * @param records the full buffered window of received raw records.
     * @param weightKg body weight for the calorie estimate, or null if unknown.
     * @param heightMeters body height for the stride estimate, or null.
     */
    fun derive(
        records: List<ReceivedRecord>,
        weightKg: Double?,
        heightMeters: Double?,
    ): List<Record> {
        if (records.isEmpty()) return emptyList()
        val out = mutableListOf<Record>()

        val byType = records.groupBy { it.type }
        val hr = byType["HeartRate"].orEmpty().sortedBy { it.timestamp }
        val steps = byType["Steps"].orEmpty().sortedBy { it.timestamp }
        val pressure = byType["Pressure"].orEmpty().sortedBy { it.timestamp }

        out += distanceRecords(steps, heightMeters)
        out += elevationAndFloors(pressure)
        out += restingHeartRate(hr)
        out += activeCalories(hr, weightKg)
        out += sleepSessions(hr)
        return out
    }

    // Distance = Σ step deltas × stride, summed per calendar day.
    private fun distanceRecords(steps: List<ReceivedRecord>, heightMeters: Double?): List<Record> {
        val stride = heightMeters?.let { it * STRIDE_HEIGHT_FACTOR } ?: DEFAULT_STRIDE_METERS
        return steps.groupBy { dateOf(it.timestamp) }.mapNotNull { (day, rows) ->
            val meters = rows.sumOf { it.delta } * stride
            if (meters <= 0.0) return@mapNotNull null
            val (start, end) = dayBounds(day, rows)
            DistanceRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                distance = Length.meters(meters),
                metadata = Metadata.manualEntry(clientRecordId = "distance-$day"),
            )
        }
    }

    // Consecutive pressure readings -> altitude deltas; net positive gain per day.
    private fun elevationAndFloors(pressure: List<ReceivedRecord>): List<Record> {
        if (pressure.size < 2) return emptyList()
        val gainByDay = mutableMapOf<LocalDate, Double>()
        for (i in 1 until pressure.size) {
            val prev = pressure[i - 1]
            val cur = pressure[i]
            val delta = altitudeMeters(cur.value) - altitudeMeters(prev.value)
            if (delta > ELEVATION_NOISE_METERS) {
                val day = dateOf(cur.timestamp)
                gainByDay[day] = (gainByDay[day] ?: 0.0) + delta
            }
        }
        val out = mutableListOf<Record>()
        for ((day, gain) in gainByDay) {
            if (gain <= 0.0) continue
            val rows = pressure.filter { dateOf(it.timestamp) == day }
            val (start, end) = dayBounds(day, rows)
            out += ElevationGainedRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                elevation = Length.meters(gain),
                metadata = Metadata.manualEntry(clientRecordId = "elevation-$day"),
            )
            out += FloorsClimbedRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                floors = gain / METERS_PER_FLOOR,
                metadata = Metadata.manualEntry(clientRecordId = "floors-$day"),
            )
        }
        return out
    }

    // Resting HR = low-quantile of stationary HR readings per day.
    private fun restingHeartRate(hr: List<ReceivedRecord>): List<Record> {
        return hr.filter { it.stationary && it.value > 0.0 }
            .groupBy { dateOf(it.timestamp) }
            .mapNotNull { (day, rows) ->
                if (rows.size < MIN_RESTING_SAMPLES) return@mapNotNull null
                val sorted = rows.map { it.value }.sorted()
                val idx = ((sorted.size - 1) * RESTING_QUANTILE).toInt()
                val bpm = sorted[idx].toLong()
                if (bpm <= 0L) return@mapNotNull null
                val time = Instant.ofEpochMilli(rows.minOf { it.timestamp })
                RestingHeartRateRecord(
                    time = time,
                    zoneOffset = offset(time),
                    beatsPerMinute = bpm,
                    metadata = Metadata.manualEntry(clientRecordId = "resting-$day"),
                )
            }
    }

    // Active calories via a Keytel-style HR estimate, summed per day.
    private fun activeCalories(hr: List<ReceivedRecord>, weightKg: Double?): List<Record> {
        if (weightKg == null || weightKg <= 0.0) return emptyList()
        val kcalByDay = mutableMapOf<LocalDate, Double>()
        for (i in 1 until hr.size) {
            val prev = hr[i - 1]
            val cur = hr[i]
            val minutes = (cur.timestamp - prev.timestamp) / 60_000.0
            if (minutes <= 0.0 || minutes > MAX_SAMPLE_GAP_MIN) continue
            val perMin = keytelKcalPerMin(cur.value, weightKg)
            if (perMin <= 0.0) continue
            val day = dateOf(cur.timestamp)
            kcalByDay[day] = (kcalByDay[day] ?: 0.0) + perMin * minutes
        }
        return kcalByDay.mapNotNull { (day, kcal) ->
            if (kcal <= 0.0) return@mapNotNull null
            val rows = hr.filter { dateOf(it.timestamp) == day }
            val (start, end) = dayBounds(day, rows)
            ActiveCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                energy = Energy.kilocalories(kcal),
                metadata = Metadata.manualEntry(clientRecordId = "calories-$day"),
            )
        }
    }

    // Sleep = contiguous night-time spans of stationary + low HR, >= min duration.
    private fun sleepSessions(hr: List<ReceivedRecord>): List<Record> {
        val nightly = hr.filter {
            it.stationary && it.value in 1.0..SLEEP_HR_THRESHOLD && isNightTime(it.timestamp)
        }.sortedBy { it.timestamp }
        if (nightly.isEmpty()) return emptyList()

        val out = mutableListOf<Record>()
        var runStart = nightly.first()
        var prev = nightly.first()
        fun flush(last: ReceivedRecord) {
            val durationMs = last.timestamp - runStart.timestamp
            if (durationMs < MIN_SLEEP_DURATION_MS) return
            val start = Instant.ofEpochMilli(runStart.timestamp)
            val end = Instant.ofEpochMilli(last.timestamp)
            // Key by the wake date so a single overnight sleep upserts on later syncs.
            val day = dateOf(last.timestamp)
            out += SleepSessionRecord(
                startTime = start,
                startZoneOffset = offset(start),
                endTime = end,
                endZoneOffset = offset(end),
                stages = listOf(
                    SleepSessionRecord.Stage(
                        startTime = start,
                        endTime = end,
                        stage = SleepSessionRecord.STAGE_TYPE_SLEEPING,
                    ),
                ),
                metadata = Metadata.manualEntry(clientRecordId = "sleep-$day"),
            )
        }
        for (i in 1 until nightly.size) {
            val cur = nightly[i]
            if (cur.timestamp - prev.timestamp > SLEEP_GAP_MS) {
                flush(prev)
                runStart = cur
            }
            prev = cur
        }
        flush(prev)
        return out
    }

    // --- helpers ---

    private fun keytelKcalPerMin(bpm: Double, weightKg: Double): Double {
        // Keytel et al. (2005), sex/age-agnostic simplification with default age.
        val kcal = (-55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * DEFAULT_AGE) / 4.184
        return kcal.coerceAtLeast(0.0)
    }

    // Standard barometric (hypsometric) altitude from station pressure in hPa.
    private fun altitudeMeters(hpa: Double): Double =
        44_330.0 * (1.0 - (hpa / SEA_LEVEL_HPA).pow(0.190_294))

    private fun dateOf(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun offset(instant: Instant) = zone.rules.getOffset(instant)

    // Span the actual first..last row of the day (clamped so end > start).
    private fun dayBounds(day: LocalDate, rows: List<ReceivedRecord>): Pair<Instant, Instant> {
        val start = Instant.ofEpochMilli(rows.minOf { it.timestamp })
        var end = Instant.ofEpochMilli(rows.maxOf { it.timestamp })
        if (!end.isAfter(start)) end = start.plusSeconds(1)
        return start to end
    }

    private fun isNightTime(ms: Long): Boolean {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        // Overnight window wraps midnight: [20:00, 24:00) ∪ [00:00, 10:00).
        return t >= NIGHT_START || t < NIGHT_END
    }

    companion object {
        private const val STRIDE_HEIGHT_FACTOR = 0.415
        private const val DEFAULT_STRIDE_METERS = 0.762
        private const val SEA_LEVEL_HPA = 1_013.25
        private const val METERS_PER_FLOOR = 3.0
        private const val ELEVATION_NOISE_METERS = 0.5
        private const val RESTING_QUANTILE = 0.05
        private const val MIN_RESTING_SAMPLES = 3
        private const val DEFAULT_AGE = 30.0
        private const val MAX_SAMPLE_GAP_MIN = 5.0
        private const val SLEEP_HR_THRESHOLD = 60.0
        private const val MIN_SLEEP_DURATION_MS = 3 * 60 * 60 * 1000L
        private const val SLEEP_GAP_MS = 30 * 60 * 1000L
        private val NIGHT_START: LocalTime = LocalTime.of(20, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(10, 0)
    }
}
