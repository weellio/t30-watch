package com.t30watch.data.db

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Local store for everything from the watch; the UI reads from here, never from BLE.
 * v1: known_device. v2: measurement (manual live measurements). v3: synced history (see HistoryEntities.kt).
 */
@Database(
    entities = [
        KnownDeviceEntity::class, MeasurementEntity::class,
        ActivitySlotEntity::class, HeartSampleEntity::class, BpSampleEntity::class,
        SleepSessionEntity::class, WorkoutEntity::class, DayTotalEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class T30Database : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun historyDao(): HistoryDao
}

@Entity(tableName = "known_device")
data class KnownDeviceEntity(
    @PrimaryKey val mac: String,
    val name: String,
    val lastConnectedAt: Long,
)

@Dao
interface KnownDeviceDao {
    @Query("SELECT * FROM known_device ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<KnownDeviceEntity>>

    @Upsert
    suspend fun upsert(device: KnownDeviceEntity)
}

/** A measurement started by hand from the Measure screen. [kind] is [KIND_HEART_RATE] or [KIND_BLOOD_PRESSURE]. */
@Entity(tableName = "measurement", indices = [Index("startedAt")])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val bpmLast: Int? = null,
    val bpmMin: Int? = null,
    val bpmAvg: Int? = null,
    val bpmMax: Int? = null,
    val bpmSamples: Int? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
) {
    companion object {
        const val KIND_HEART_RATE = "heart_rate"
        const val KIND_BLOOD_PRESSURE = "blood_pressure"
    }
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(m: MeasurementEntity): Long

    @Query("SELECT * FROM measurement ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurement WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun between(from: Long, to: Long): List<MeasurementEntity>
}
