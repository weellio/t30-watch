package com.t30watch.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * History synced from the watch. App-level shapes, independent of the SDK's models.
 * Times are epoch milliseconds in the phone's clock. Primary keys make re-syncing the same slot an update,
 * not a duplicate, so a sync can always safely re-read the last day or two.
 */

/** Activity for one time slot (the watch keeps 5- or 30-minute slots). */
@Entity(tableName = "activity_slot", primaryKeys = ["mac", "startMs"], indices = [Index("startMs")])
data class ActivitySlotEntity(
    val mac: String,
    val startMs: Long,
    val minutes: Int,
    val steps: Int,
    val distanceM: Int?,
    val kcal: Double?,
)

/** One heart-rate value at a point in time (from the watch's automatic monitoring). */
@Entity(tableName = "heart_sample", primaryKeys = ["mac", "timeMs"], indices = [Index("timeMs")])
data class HeartSampleEntity(
    val mac: String,
    val timeMs: Long,
    val bpm: Int,
)

/** One blood-pressure value (automatic monitoring). */
@Entity(tableName = "bp_sample", primaryKeys = ["mac", "timeMs"], indices = [Index("timeMs")])
data class BpSampleEntity(
    val mac: String,
    val timeMs: Long,
    val systolic: Int,
    val diastolic: Int,
)

/** One night (or nap). Minutes are as reported by the watch. [stages] is a compact per-minute string, may be empty. */
@Entity(tableName = "sleep_session", primaryKeys = ["mac", "startMs"], indices = [Index("startMs")])
data class SleepSessionEntity(
    val mac: String,
    val startMs: Long,
    val endMs: Long,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    val wakeCount: Int,
    /** Watch's own 0–100 style score if it gives one. */
    val quality: Int?,
    /** One char per minute from start: 'D' deep, 'L' light, 'W' awake. Empty if the watch gave no curve. */
    val stages: String,
)

/** A workout recorded with the watch's sport mode. */
@Entity(tableName = "workout", primaryKeys = ["mac", "startMs"], indices = [Index("startMs")])
data class WorkoutEntity(
    val mac: String,
    val startMs: Long,
    val endMs: Long,
    val sportType: Int,
    val sportName: String,
    val steps: Int?,
    val distanceM: Int?,
    val kcal: Double?,
    val avgBpm: Int?,
    val maxBpm: Int?,
)

/** Per-day totals as the watch reports them (more accurate than summing slots for today's steps). */
@Entity(tableName = "day_total", primaryKeys = ["mac", "day"])
data class DayTotalEntity(
    val mac: String,
    /** yyyy-MM-dd in the phone's time zone. */
    val day: String,
    val steps: Int,
    val distanceM: Int?,
    val kcal: Double?,
    val updatedAt: Long,
)

data class DayAgg(val day: String, val steps: Int, val distanceM: Int?, val kcal: Double?)
data class BucketAgg(val bucketStart: Long, val steps: Int)
data class HeartAgg(val bucketStart: Long, val minBpm: Int, val avgBpm: Double, val maxBpm: Int, val n: Int)

@Dao
interface HistoryDao {
    // ---- writes (upserts: re-syncing a slot just replaces it)
    @Upsert suspend fun upsertSlots(rows: List<ActivitySlotEntity>)
    @Upsert suspend fun upsertHeart(rows: List<HeartSampleEntity>)
    @Upsert suspend fun upsertBp(rows: List<BpSampleEntity>)
    @Upsert suspend fun upsertSleep(rows: List<SleepSessionEntity>)
    @Upsert suspend fun upsertWorkouts(rows: List<WorkoutEntity>)
    @Upsert suspend fun upsertDayTotal(row: DayTotalEntity)

    // ---- reads for charts
    @Query("SELECT * FROM activity_slot WHERE startMs >= :from AND startMs < :to ORDER BY startMs")
    fun slots(from: Long, to: Long): Flow<List<ActivitySlotEntity>>

    @Query("SELECT * FROM heart_sample WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    fun heart(from: Long, to: Long): Flow<List<HeartSampleEntity>>

    @Query("SELECT * FROM bp_sample WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    fun bp(from: Long, to: Long): Flow<List<BpSampleEntity>>

    @Query("SELECT * FROM sleep_session WHERE endMs >= :from AND startMs < :to ORDER BY startMs")
    fun sleep(from: Long, to: Long): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM workout WHERE startMs >= :from AND startMs < :to ORDER BY startMs DESC")
    fun workouts(from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM day_total WHERE day >= :fromDay AND day <= :toDay ORDER BY day")
    fun dayTotals(fromDay: String, toDay: String): Flow<List<DayTotalEntity>>

    @Query("SELECT * FROM heart_sample ORDER BY timeMs DESC LIMIT 1")
    fun latestHeart(): Flow<HeartSampleEntity?>

    @Query("SELECT * FROM bp_sample ORDER BY timeMs DESC LIMIT 1")
    fun latestBp(): Flow<BpSampleEntity?>

    @Query("SELECT * FROM sleep_session ORDER BY startMs DESC LIMIT 1")
    fun latestSleep(): Flow<SleepSessionEntity?>

    // ---- one-shot reads for export
    @Query("SELECT * FROM activity_slot WHERE startMs >= :from AND startMs < :to ORDER BY startMs")
    suspend fun slotsOnce(from: Long, to: Long): List<ActivitySlotEntity>

    @Query("SELECT * FROM heart_sample WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    suspend fun heartOnce(from: Long, to: Long): List<HeartSampleEntity>

    @Query("SELECT * FROM bp_sample WHERE timeMs >= :from AND timeMs < :to ORDER BY timeMs")
    suspend fun bpOnce(from: Long, to: Long): List<BpSampleEntity>

    @Query("SELECT * FROM sleep_session WHERE startMs >= :from AND startMs < :to ORDER BY startMs")
    suspend fun sleepOnce(from: Long, to: Long): List<SleepSessionEntity>

    @Query("SELECT * FROM workout WHERE startMs >= :from AND startMs < :to ORDER BY startMs")
    suspend fun workoutsOnce(from: Long, to: Long): List<WorkoutEntity>

    @Query("SELECT * FROM day_total WHERE day >= :fromDay AND day <= :toDay ORDER BY day")
    suspend fun dayTotalsOnce(fromDay: String, toDay: String): List<DayTotalEntity>

    @Query("SELECT MIN(startMs) FROM activity_slot")
    suspend fun firstSlotMs(): Long?

    @Query("SELECT COUNT(*) FROM activity_slot") suspend fun countSlots(): Int
    @Query("SELECT COUNT(*) FROM heart_sample") suspend fun countHeart(): Int
    @Query("SELECT COUNT(*) FROM bp_sample") suspend fun countBp(): Int
    @Query("SELECT COUNT(*) FROM sleep_session") suspend fun countSleep(): Int
    @Query("SELECT COUNT(*) FROM workout") suspend fun countWorkouts(): Int
}
