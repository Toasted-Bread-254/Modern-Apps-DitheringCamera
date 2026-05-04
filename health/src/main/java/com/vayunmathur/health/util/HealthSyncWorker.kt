package com.vayunmathur.health.util
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.health.CLASSES
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Worker that ensures local Room DB is in sync with Health Connect.
 */
class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
        val db = applicationContext.buildDatabase<HealthDatabase>()
        val ds = DataStoreUtils.getInstance(applicationContext)
        var token = ds.getString("hc_token")
        if (token == null) {
            // 2. If no token exists, initialize one for the data types you care about
            CLASSES.forEach { clazz ->
                var pageToken: String? = null
                do {
                    val records = healthConnectClient.readRecords(ReadRecordsRequest(clazz, TimeRangeFilter.after(Instant.EPOCH), pageSize = 5000, pageToken = pageToken))
                    db.healthDao().upsert(records.records.flatMap { it.toRecord() })
                    pageToken = records.pageToken
                } while (pageToken != null)
                println("Completed inserting ${clazz.simpleName}")
            }
            token = healthConnectClient.getChangesToken(
                ChangesTokenRequest(
                    recordTypes = CLASSES
                )
            )
            ds.setString("hc_token", token)
        }
        do {
            val response = healthConnectClient.getChanges(token!!)

            // Handle new/updated records
            val upsertedRecords = response.changes.filterIsInstance<UpsertionChange>().map { it.record }

            val newRecords = upsertedRecords.flatMap {
                it.toRecord()
            }
            db.healthDao().upsert(newRecords)
            println("Upserted ${newRecords.size} records")

            // Handle deleted records
            val deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            db.healthDao().deleteByIds(deletedIds)

            // Update token for the next iteration/sync
            token = response.nextChangesToken
            ds.setString("hc_token", token)

            println("Deleted ${deletedIds.size} records")

        } while (response.hasMore)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            val syncRequestNow = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "HealthSyncWork",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            WorkManager.getInstance(context).enqueueUniqueWork("HealthSyncWork_now", ExistingWorkPolicy.KEEP, syncRequestNow)
        }
    }
}

fun androidx.health.connect.client.records.Record.toRecord(): List<Record> =
    when (this) {
        // --- Activity ---
        is StepsRecord -> listOf(Record(this.metadata.id, 0, RecordType.Steps, this.startTime, this.endTime, this.count.toDouble(), metadata = "Steps"))
        is WheelchairPushesRecord -> listOf(Record(this.metadata.id, 0, RecordType.Wheelchair, this.startTime, this.endTime, this.count.toDouble(), metadata = "Wheelchair Pushes"))
        is DistanceRecord -> listOf(Record(this.metadata.id, 0, RecordType.Distance, this.startTime, this.endTime, this.distance.inKilometers, metadata = "Distance"))
        is TotalCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesTotal, this.startTime, this.endTime, this.energy.inKilocalories, metadata = "Total Calories"))
        is ActiveCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesActive, this.startTime, this.endTime, this.energy.inKilocalories, metadata = "Active Calories"))
        is BasalMetabolicRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesBasal, this.time, this.time, this.basalMetabolicRate.inKilocaloriesPerDay, metadata = "Basal Metabolic Rate"))
        is FloorsClimbedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Floors, this.startTime, this.endTime, this.floors, metadata = "Floors Climbed"))
        is ElevationGainedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Elevation, this.startTime, this.endTime, this.elevation.inMeters, metadata = "Elevation Gained"))

        // --- Vitals ---
        is HeartRateRecord -> this.samples.mapIndexed { idx, sample -> Record(this.metadata.id, idx, RecordType.HeartRate, sample.time, sample.time, sample.beatsPerMinute.toDouble(), metadata = "Heart Rate") }
        is RestingHeartRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RestingHeartRate, this.time, this.time, this.beatsPerMinute.toDouble(), metadata = "Resting Heart Rate"))
        is HeartRateVariabilityRmssdRecord -> listOf(Record(this.metadata.id, 0, RecordType.HeartRateVariabilityRmssd, this.time, this.time, this.heartRateVariabilityMillis, metadata = "HRV"))
        is RespiratoryRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RespiratoryRate, this.time, this.time, this.rate, metadata = "Respiratory Rate"))
        is OxygenSaturationRecord -> listOf(Record(this.metadata.id, 0, RecordType.OxygenSaturation, this.time, this.time, this.percentage.value, metadata = "Oxygen Saturation"))
        is BloodPressureRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodPressure, this.time, this.time, this.systolic.inMillimetersOfMercury, this.diastolic.inMillimetersOfMercury, metadata = "Blood Pressure"))
        is BloodGlucoseRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodGlucose, this.time, this.time, this.level.inMilligramsPerDeciliter, metadata = "Blood Glucose"))
        is Vo2MaxRecord -> listOf(Record(this.metadata.id, 0, RecordType.Vo2Max, this.time, this.time, this.vo2MillilitersPerMinuteKilogram, metadata = "VO2 Max"))
        is SkinTemperatureRecord -> this.deltas.mapIndexed { idx, delta -> Record(this.metadata.id, idx, RecordType.SkinTemperature, delta.time, delta.time, delta.delta.inCelsius, metadata = "Skin Temperature") }

        // --- Body Composthision ---
        is WeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Weight, this.time, this.time, this.weight.inKilograms, metadata = "Weight"))
        is HeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Height, this.time, this.time, this.height.inMeters, metadata = "Height"))
        is BodyFatRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyFat, this.time, this.time, this.percentage.value, metadata = "Body Fat"))
        is LeanBodyMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.LeanBodyMass, this.time, this.time, this.mass.inKilograms, metadata = "Lean Body Mass"))
        is BoneMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BoneMass, this.time, this.time, this.mass.inKilograms, metadata = "Bone Mass"))
        is BodyWaterMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyWaterMass, this.time, this.time, this.mass.inKilograms, metadata = "Body Water Mass"))

        // --- Lifestyle ---
//                    is SleepSessionRecord -> listOf(Record(this.metadata.id, 0, "Sleep", this.startTime, this.endTime, this))
        is MindfulnessSessionRecord -> listOf(Record(this.metadata.id, 0, RecordType.Mindfulness, this.startTime, this.endTime, Duration.between(this.startTime, this.endTime).toMillis().toDouble() / 1000.0 / 60.0, metadata = "Mindfulness"))
        is HydrationRecord -> listOf(Record(this.metadata.id, 0, RecordType.Hydration, this.startTime, this.endTime, this.volume.inMilliliters, metadata = "Hydration"))
        is NutritionRecord -> listOf(Record(
            this.metadata.id, 0, RecordType.Nutrition, this.startTime, this.endTime,
            this.energy?.inKilocalories ?: 0.0,
            nutritionData = com.vayunmathur.health.data.NutritionData(
                protein = this.protein?.inGrams ?: 0.0,
                carbohydrates = this.totalCarbohydrate?.inGrams ?: 0.0,
                fat = this.totalFat?.inGrams ?: 0.0,
                fiber = this.dietaryFiber?.inGrams ?: 0.0,
                sugar = this.sugar?.inGrams ?: 0.0,
                sodium = this.sodium?.inGrams ?: 0.0,
                biotin = this.biotin?.inGrams ?: 0.0,
                caffeine = this.caffeine?.inGrams ?: 0.0,
                calcium = this.calcium?.inGrams ?: 0.0,
                chloride = this.chloride?.inGrams ?: 0.0,
                cholesterol = this.cholesterol?.inGrams ?: 0.0,
                chromium = this.chromium?.inGrams ?: 0.0,
                copper = this.copper?.inGrams ?: 0.0,
                folate = this.folate?.inGrams ?: 0.0,
                folicAcid = this.folicAcid?.inGrams ?: 0.0,
                iodine = this.iodine?.inGrams ?: 0.0,
                iron = this.iron?.inGrams ?: 0.0,
                magnesium = this.magnesium?.inGrams ?: 0.0,
                manganese = this.manganese?.inGrams ?: 0.0,
                molybdenum = this.molybdenum?.inGrams ?: 0.0,
                monounsaturatedFat = this.monounsaturatedFat?.inGrams ?: 0.0,
                niacin = this.niacin?.inGrams ?: 0.0,
                pantothenicAcid = this.pantothenicAcid?.inGrams ?: 0.0,
                phosphorus = this.phosphorus?.inGrams ?: 0.0,
                polyunsaturatedFat = this.polyunsaturatedFat?.inGrams ?: 0.0,
                potassium = this.potassium?.inGrams ?: 0.0,
                riboflavin = this.riboflavin?.inGrams ?: 0.0,
                saturatedFat = this.saturatedFat?.inGrams ?: 0.0,
                selenium = this.selenium?.inGrams ?: 0.0,
                thiamin = this.thiamin?.inGrams ?: 0.0,
                transFat = this.transFat?.inGrams ?: 0.0,
                unsaturatedFat = this.unsaturatedFat?.inGrams ?: 0.0,
                vitaminA = this.vitaminA?.inGrams ?: 0.0,
                vitaminB12 = this.vitaminB12?.inGrams ?: 0.0,
                vitaminB6 = this.vitaminB6?.inGrams ?: 0.0,
                vitaminC = this.vitaminC?.inGrams ?: 0.0,
                vitaminD = this.vitaminD?.inGrams ?: 0.0,
                vitaminE = this.vitaminE?.inGrams ?: 0.0,
                vitaminK = this.vitaminK?.inGrams ?: 0.0,
                zinc = this.zinc?.inGrams ?: 0.0
            ),
            metadata = this.name
        ))

        else -> throw IllegalArgumentException("Unsupported record type")
    }