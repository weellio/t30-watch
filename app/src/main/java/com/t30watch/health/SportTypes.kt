package com.t30watch.health

import androidx.health.connect.client.records.ExerciseSessionRecord

/** Maps a workout's name (as we store it) to a Health Connect exercise type. Unknown → "other workout". */
object SportTypes {
    fun healthConnectType(name: String): Int {
        val n = name.lowercase()
        return when {
            "run" in n -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            "walk" in n -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            "hik" in n || "climb" in n -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            "cycl" in n || "bike" in n || "spin" in n -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            "swim" in n -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            "yoga" in n -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            "badminton" in n -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
            "basketball" in n -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
            "football" in n || "soccer" in n -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
            "tennis" in n -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
            "rope" in n || "skip" in n || "jump" in n -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
            "dance" in n -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
            "row" in n -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            "elliptical" in n -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            "strength" in n || "weight" in n || "gym" in n -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }
}
