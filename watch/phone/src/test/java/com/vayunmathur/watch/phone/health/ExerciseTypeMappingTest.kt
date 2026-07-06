package com.vayunmathur.watch.phone.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the two-enum mapping from Health Services ExerciseType names to Health
 * Connect EXERCISE_TYPE_* ints. The two systems share neither names nor ids, so a
 * silent drift here would mislabel every synced workout.
 */
class ExerciseTypeMappingTest {

    @Test fun mapsKnownTypes() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, healthConnectExerciseType("WALKING"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, healthConnectExerciseType("RUNNING"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, healthConnectExerciseType("BIKING"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, healthConnectExerciseType("HIKING"))
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            healthConnectExerciseType("SWIMMING_POOL"),
        )
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            healthConnectExerciseType("STRENGTH_TRAINING"),
        )
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            healthConnectExerciseType("WORKOUT"),
        )
    }

    @Test fun unknownFallsBackToOtherWorkout() {
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            healthConnectExerciseType("SOMETHING_ELSE"),
        )
    }
}
