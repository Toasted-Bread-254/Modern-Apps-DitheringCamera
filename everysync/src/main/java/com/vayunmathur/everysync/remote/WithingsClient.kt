package com.vayunmathur.everysync.remote

import android.util.Log
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.library.network.NetworkClient
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Withings REST client. Pulls body + vitals measurements and maps them to
 * [RemoteMeasurement]s for Health Connect. Each measure's stable group id makes
 * the Health Connect clientRecordId idempotent across syncs.
 */
class WithingsClient(private val accessToken: String) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Pull measurements updated since [sinceEpochSecs] (0 = all). */
    suspend fun getMeasurements(sinceEpochSecs: Long): List<RemoteMeasurement> {
        val out = mutableListOf<RemoteMeasurement>()
        try {
            val body = buildString {
                append("action=getmeas&meastypes=1,6,9,10,11")
                if (sinceEpochSecs > 0) append("&lastupdate=$sinceEpochSecs")
            }
            val resp = NetworkClient.performRequest(
                "https://wbsapi.withings.net/measure", "POST",
                mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                body,
            )
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: return out
            val groups = (root["body"] as? JsonObject)?.get("measuregrps") as? JsonArray ?: return out
            for (g in groups) {
                val grp = g.jsonObject
                val grpId = grp["grpid"]?.jsonPrimitive?.content ?: continue
                val date = (grp["date"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue) * 1000
                (grp["measures"] as? JsonArray)?.forEach { m ->
                    val measure = m.jsonObject
                    val type = measure["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                    val value = measure["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                    val unit = measure["unit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val real = value * 10.0.pow(unit)
                    val mapped = mapType(type) ?: return@forEach
                    out += RemoteMeasurement(
                        clientRecordId = "withings:$grpId:$type",
                        type = mapped,
                        value = if (mapped == MeasurementType.BODY_FAT) real else real,
                        timeMillis = date,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMeasurements failed", e)
        }
        return out
    }

    private fun mapType(withingsType: Int): MeasurementType? = when (withingsType) {
        1 -> MeasurementType.WEIGHT
        6 -> MeasurementType.BODY_FAT
        11 -> MeasurementType.HEART_RATE
        else -> null // 9/10 (blood pressure) intentionally skipped for now
    }

    companion object {
        private const val TAG = "WithingsClient"
    }
}
