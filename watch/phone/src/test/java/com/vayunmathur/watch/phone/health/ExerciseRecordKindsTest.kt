package com.vayunmathur.watch.phone.health

import com.vayunmathur.watch.shared.data.ExerciseSessionSummary
import com.vayunmathur.watch.shared.data.LapMarker
import com.vayunmathur.watch.shared.data.RepSegment
import com.vayunmathur.watch.shared.data.RoutePoint
import com.vayunmathur.watch.shared.data.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the core invariant: an exercise session may only ever materialize
 * session-unique record kinds. Distance/Floors/Elevation/TotalCalories/Steps/
 * HeartRate already flow through the passive + per-sample pipelines, so writing
 * session-scoped copies would double-count in Health Connect.
 */
class ExerciseRecordKindsTest {

    private fun fullSummary() = ExerciseSessionSummary(
        exerciseType = "RUNNING",
        startTime = 1_000L,
        endTime = 2_000L,
        activeDurationMs = 1_000L,
        avgSpeedMps = 3.0,
        maxSpeedMps = 5.0,
        avgCadenceSpm = 170.0,
        vo2Max = 42.0,
        route = listOf(RoutePoint(1.0, 2.0, 3.0, 1_500L)),
        lapCount = 4L,
        laps = listOf(LapMarker(1_000L, 1_500L)),
        speedSamples = listOf(SpeedSample(1_200L, 3.2)),
        segments = listOf(RepSegment(0, 10, 1_000L, 2_000L)),
        endReason = "Ended by user",
        distanceMeters = 500.0,
        totalCalories = 80.0,
        avgHr = 150.0,
    )

    @Test fun fullSummaryNeverProducesForbiddenKinds() {
        val kinds = exerciseRecordKinds(fullSummary())
        assertTrue(
            "produced kinds must be within the allowed set",
            ALLOWED_EXERCISE_RECORD_KINDS.containsAll(kinds),
        )
        val producedNames = kinds.map { it.name }.toSet()
        assertTrue(
            "produced kinds must never include a double-counted type",
            producedNames.intersect(FORBIDDEN_EXERCISE_RECORD_NAMES).isEmpty(),
        )
    }

    @Test fun fullSummaryProducesEverySessionUniqueKind() {
        assertEquals(ALLOWED_EXERCISE_RECORD_KINDS, exerciseRecordKinds(fullSummary()))
    }

    @Test fun minimalSummaryStillWritesTheSession() {
        val minimal = ExerciseSessionSummary(
            exerciseType = "WORKOUT",
            startTime = 1_000L,
            endTime = 2_000L,
            activeDurationMs = 1_000L,
        )
        val kinds = exerciseRecordKinds(minimal)
        assertEquals(setOf(ExerciseRecordKind.ExerciseSession), kinds)
    }

    @Test fun displayOnlyAggregatesDoNotAddKinds() {
        // distanceMeters / totalCalories / avgHr / lapCount are display-only and
        // must not, on their own, cause any record to be written.
        val displayOnly = ExerciseSessionSummary(
            exerciseType = "WALKING",
            startTime = 1_000L,
            endTime = 2_000L,
            activeDurationMs = 1_000L,
            lapCount = 3L,
            distanceMeters = 400.0,
            totalCalories = 50.0,
            avgHr = 120.0,
        )
        assertEquals(setOf(ExerciseRecordKind.ExerciseSession), exerciseRecordKinds(displayOnly))
    }
}
