package com.vayunmathur.watch.phone.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.vayunmathur.watch.shared.data.ExerciseSessionSummary

/**
 * Pure, Android-framework-free decisions for turning an [ExerciseSessionSummary]
 * into Health Connect records. Kept separate from [HealthConnectManager] so they
 * can be unit-tested on the JVM without Robolectric, and so the "no double-count"
 * invariant lives in one auditable place.
 */

/** The kinds of records an exercise session may materialize. */
enum class ExerciseRecordKind {
    ExerciseSession,
    Route,
    Segments,
    Laps,
    Speed,
    StepsCadence,
    Vo2Max,
}

/**
 * The complete set of record kinds a session is ever allowed to write. Anything
 * outside this set (Distance, Floors, Elevation, TotalCalories, Steps, HeartRate)
 * already flows through the passive/per-sample pipelines and would double-count.
 */
val ALLOWED_EXERCISE_RECORD_KINDS: Set<ExerciseRecordKind> = ExerciseRecordKind.entries.toSet()

/**
 * Record kinds that must NEVER be produced by a session, because they duplicate
 * data already written elsewhere. Held by name so the guard is explicit in tests.
 */
val FORBIDDEN_EXERCISE_RECORD_NAMES: Set<String> =
    setOf("Distance", "Floors", "Elevation", "TotalCalories", "Steps", "HeartRate")

/**
 * Decides which record kinds [summary] will materialize. [ExerciseRecordKind.ExerciseSession]
 * is always present; the rest are gated on the corresponding session-unique data
 * actually being reported. [ExerciseRecordKind.Speed] covers both the real
 * time-series and the avg/max fallback.
 */
fun exerciseRecordKinds(summary: ExerciseSessionSummary): Set<ExerciseRecordKind> = buildSet {
    add(ExerciseRecordKind.ExerciseSession)
    if (!summary.route.isNullOrEmpty()) add(ExerciseRecordKind.Route)
    if (!summary.segments.isNullOrEmpty()) add(ExerciseRecordKind.Segments)
    if (!summary.laps.isNullOrEmpty()) add(ExerciseRecordKind.Laps)
    if (!summary.speedSamples.isNullOrEmpty() || summary.avgSpeedMps != null) add(ExerciseRecordKind.Speed)
    if (summary.avgCadenceSpm != null) add(ExerciseRecordKind.StepsCadence)
    if (summary.vo2Max != null) add(ExerciseRecordKind.Vo2Max)
}

/**
 * Maps a Health Services ExerciseType.name to the Health Connect EXERCISE_TYPE_*
 * int. The two enum systems differ, so the mapping is explicit.
 */
fun healthConnectExerciseType(hsName: String): Int = when (hsName) {
    "WALKING" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    "RUNNING" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    "BIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    "HIKING" -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    "SWIMMING_POOL" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    "STRENGTH_TRAINING" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}
