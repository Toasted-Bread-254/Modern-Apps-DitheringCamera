package com.vayunmathur.watch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.vayunmathur.watch.phone.data.WatchRecord
import java.time.Instant
import java.time.ZoneId

/**
 * Wraps Health Connect onboarding, permissions, and inserting the watch's
 * heart-rate and step records.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(FloorsClimbedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun insert(records: List<WatchRecord>) {
        if (records.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val hcRecords = records.mapNotNull { record ->
            val time = Instant.ofEpochMilli(record.timestamp)
            val offset = zone.rules.getOffset(time)
            when (record.type) {
                "HeartRate" -> HeartRateRecord(
                    startTime = time,
                    startZoneOffset = offset,
                    endTime = time,
                    endZoneOffset = offset,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = time,
                            beatsPerMinute = record.value.toLong(),
                        ),
                    ),
                    metadata = Metadata.manualEntry(clientRecordId = "hr-${record.id}"),
                )
                "Steps" -> {
                    val count = record.delta.toLong()
                    if (count <= 0L) return@mapNotNull null
                    val start = time.minusSeconds(STEP_WINDOW_SECONDS)
                    StepsRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        count = count,
                        metadata = Metadata.manualEntry(clientRecordId = "steps-${record.id}"),
                    )
                }
                else -> null
            }
        }
        if (hcRecords.isEmpty()) return
        try {
            client().insertRecords(hcRecords)
        } catch (e: Exception) {
            Log.e(TAG, "insertRecords failed", e)
        }
    }

    /** Batch-inserts derivation output; clientRecordIds make this idempotent. */
    suspend fun insertDerived(records: List<Record>) {
        if (records.isEmpty()) return
        try {
            client().insertRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "insertDerived failed", e)
        }
    }

    /** Most recent body weight in kilograms, or null if none recorded. */
    suspend fun latestWeightKg(): Double? = try {
        client().readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.weight?.inKilograms
    } catch (e: Exception) {
        Log.e(TAG, "latestWeightKg failed", e)
        null
    }

    /** Most recent body height in meters, or null if none recorded. */
    suspend fun latestHeightMeters(): Double? = try {
        client().readRecords(
            ReadRecordsRequest(
                recordType = HeightRecord::class,
                timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()?.height?.inMeters
    } catch (e: Exception) {
        Log.e(TAG, "latestHeightMeters failed", e)
        null
    }

    suspend fun writeWeight(kg: Double) {
        if (kg <= 0.0) return
        val time = Instant.now()
        val offset = ZoneId.systemDefault().rules.getOffset(time)
        try {
            client().insertRecords(
                listOf(
                    WeightRecord(
                        time = time,
                        zoneOffset = offset,
                        weight = Mass.kilograms(kg),
                        metadata = Metadata.manualEntry(),
                    ),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "writeWeight failed", e)
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val STEP_WINDOW_SECONDS = 60L
    }
}
