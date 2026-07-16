package com.vayunmathur.everysync.sink

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import java.time.Instant
import java.time.ZoneOffset

/**
 * Writes [RemoteMeasurement]s into Health Connect. Uses a stable
 * `clientRecordId` per measurement so re-running a sync upserts rather than
 * duplicating. Mirrors the record-construction patterns in `health`'s HealthAPI.
 */
object HealthSink {
    private const val TAG = "HealthSink"

    suspend fun upsert(context: Context, measurements: List<RemoteMeasurement>) {
        if (measurements.isEmpty()) return
        val client = HealthConnectClient.getOrCreate(context)
        val records = measurements.mapNotNull { toRecord(it) }
        if (records.isEmpty()) return
        try {
            client.insertRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "insertRecords failed", e)
        }
    }

    private fun toRecord(m: RemoteMeasurement): Record? {
        val time = Instant.ofEpochMilli(m.timeMillis)
        val offset = ZoneOffset.systemDefault().rules.getOffset(time)
        val meta = Metadata.manualEntry(clientRecordId = m.clientRecordId)
        return when (m.type) {
            MeasurementType.WEIGHT ->
                WeightRecord(time = time, zoneOffset = offset, weight = Mass.kilograms(m.value), metadata = meta)
            MeasurementType.HEIGHT ->
                HeightRecord(time = time, zoneOffset = offset, height = Length.meters(m.value), metadata = meta)
            MeasurementType.BODY_FAT ->
                BodyFatRecord(time = time, zoneOffset = offset, percentage = Percentage(m.value), metadata = meta)
            MeasurementType.OXYGEN_SATURATION ->
                OxygenSaturationRecord(time = time, zoneOffset = offset, percentage = Percentage(m.value), metadata = meta)
            MeasurementType.RESTING_HEART_RATE ->
                RestingHeartRateRecord(time = time, zoneOffset = offset, beatsPerMinute = m.value.toLong(), metadata = meta)
            MeasurementType.HEART_RATE -> HeartRateRecord(
                startTime = time,
                startZoneOffset = offset,
                endTime = time,
                endZoneOffset = offset,
                samples = listOf(HeartRateRecord.Sample(time, m.value.toLong())),
                metadata = meta,
            )
            MeasurementType.STEPS -> StepsRecord(
                startTime = time,
                startZoneOffset = offset,
                endTime = time.plusSeconds(60),
                endZoneOffset = offset,
                count = m.value.toLong().coerceAtLeast(1),
                metadata = meta,
            )
        }
    }
}
