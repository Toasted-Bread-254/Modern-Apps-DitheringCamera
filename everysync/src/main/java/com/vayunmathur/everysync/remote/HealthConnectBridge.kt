package com.vayunmathur.everysync.remote

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest

/**
 * Bridge for providers serviced through Health Connect (Samsung Health, Google
 * Health). Their data already lives in Health Connect on-device, so this simply
 * walks the changes feed to confirm/track records rather than making a cloud
 * call. Returns the number of upserted records seen and the next changes token.
 */
object HealthConnectBridge {
    private const val TAG = "HealthConnectBridge"

    private val RECORD_TYPES = setOf(
        WeightRecord::class,
        StepsRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
    )

    data class Result(val upserted: Int, val deleted: Int, val nextToken: String)

    /** Walk the changes feed from [token] (null = initialize a fresh token). */
    suspend fun pullChanges(context: Context, token: String?): Result {
        val client = HealthConnectClient.getOrCreate(context)
        var current = token ?: client.getChangesToken(ChangesTokenRequest(recordTypes = RECORD_TYPES))
        var upserted = 0
        var deleted = 0
        try {
            var hasMore = true
            while (hasMore) {
                val response = client.getChanges(current)
                upserted += response.changes.count {
                    it is androidx.health.connect.client.changes.UpsertionChange
                }
                deleted += response.changes.count {
                    it is androidx.health.connect.client.changes.DeletionChange
                }
                current = response.nextChangesToken
                hasMore = response.hasMore
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullChanges failed", e)
        }
        return Result(upserted, deleted, current)
    }
}
