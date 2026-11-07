package com.t30watch.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class Sex { MALE, FEMALE }

/** What the app shows and accepts. Storage is always metric. */
enum class Units { IMPERIAL, METRIC }

enum class DarkMode { SYSTEM, LIGHT, DARK }

/** Body data the watch uses for its calorie and distance maths. Sent with syncPersonInfo. */
data class Profile(
    val heightCm: Int = 175,
    val weightKg: Int = 75,
    val age: Int = 40,
    val sex: Sex = Sex.MALE,
    val stepGoal: Int = 8_000,
    val sleepGoalMinutes: Int = 480,
    /** False until the user has saved the profile once. Until then nothing is sent to the watch. */
    val saved: Boolean = false,
)

data class Settings(
    /** Watch password sent in confirmDevicePwd. Factory value is 0000. */
    val password: String = "0000",
    /** Clock format pushed to the watch on every connect. */
    val use24Hour: Boolean = true,
    /** Ask the watch to show a "confirm connection" prompt (only some models support it). */
    val confirmOnWatch: Boolean = false,
    /** Write every raw SDK callback to the rotating log file. */
    val rawLogging: Boolean = true,
    /** Also run the SDK's own logger (raw BLE bytes) in Android/data/com.t30watch/files/VpSDK. */
    val sdkLogging: Boolean = true,
    /** Run a foreground service that keeps the watch connected and reconnects when it drops. */
    val keepConnected: Boolean = true,
    /** Pull history from the watch this often while connected. 0 = only by hand. */
    val autoSyncMinutes: Int = 60,
    /** Forward phone notifications to the watch (needs Notification access). */
    val forwardNotifications: Boolean = false,
    /** Package names whose notifications are never forwarded. */
    val mutedApps: Set<String> = emptySet(),
    /** Write synced data to Health Connect after each sync. */
    val healthConnectAutoExport: Boolean = false,
    val profile: Profile = Profile(),
    /** Units shown in the app (ft/in, lb, miles by default). */
    val units: Units = Units.IMPERIAL,
    /** Colour style id from ui/theme/Palettes.kt. */
    val themeId: String = "wallpaper",
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** Pure-black backgrounds in dark mode (saves battery on OLED). */
    val amoled: Boolean = false,
)

/** App preferences, stored with DataStore (Android's replacement for SharedPreferences). */
@Singleton
class AppSettings @Inject constructor(@param:ApplicationContext private val context: Context) {
    private object Keys {
        val PASSWORD = stringPreferencesKey("password")
        val USE_24H = booleanPreferencesKey("use_24h")
        val CONFIRM_ON_WATCH = booleanPreferencesKey("confirm_on_watch")
        val RAW_LOGGING = booleanPreferencesKey("raw_logging")
        val SDK_LOGGING = booleanPreferencesKey("sdk_logging")
        val KEEP_CONNECTED = booleanPreferencesKey("keep_connected")
        val AUTO_SYNC_MINUTES = intPreferencesKey("auto_sync_minutes")
        val FORWARD_NOTIFICATIONS = booleanPreferencesKey("forward_notifications")
        val MUTED_APPS = stringSetPreferencesKey("muted_apps")
        val HC_AUTO_EXPORT = booleanPreferencesKey("hc_auto_export")
        val HEIGHT = intPreferencesKey("profile_height_cm")
        val WEIGHT = intPreferencesKey("profile_weight_kg")
        val AGE = intPreferencesKey("profile_age")
        val SEX = stringPreferencesKey("profile_sex")
        val STEP_GOAL = intPreferencesKey("profile_step_goal")
        val SLEEP_GOAL = intPreferencesKey("profile_sleep_goal_min")
        val PROFILE_SAVED = booleanPreferencesKey("profile_saved")
        val UNITS = stringPreferencesKey("units")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val AMOLED = booleanPreferencesKey("amoled")
    }

    private val defaults = Settings()

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val dp = defaults.profile
        Settings(
            password = p[Keys.PASSWORD] ?: defaults.password,
            use24Hour = p[Keys.USE_24H] ?: defaults.use24Hour,
            confirmOnWatch = p[Keys.CONFIRM_ON_WATCH] ?: defaults.confirmOnWatch,
            rawLogging = p[Keys.RAW_LOGGING] ?: defaults.rawLogging,
            sdkLogging = p[Keys.SDK_LOGGING] ?: defaults.sdkLogging,
            keepConnected = p[Keys.KEEP_CONNECTED] ?: defaults.keepConnected,
            autoSyncMinutes = p[Keys.AUTO_SYNC_MINUTES] ?: defaults.autoSyncMinutes,
            forwardNotifications = p[Keys.FORWARD_NOTIFICATIONS] ?: defaults.forwardNotifications,
            mutedApps = p[Keys.MUTED_APPS] ?: defaults.mutedApps,
            healthConnectAutoExport = p[Keys.HC_AUTO_EXPORT] ?: defaults.healthConnectAutoExport,
            profile = Profile(
                heightCm = p[Keys.HEIGHT] ?: dp.heightCm,
                weightKg = p[Keys.WEIGHT] ?: dp.weightKg,
                age = p[Keys.AGE] ?: dp.age,
                sex = p[Keys.SEX]?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: dp.sex,
                stepGoal = p[Keys.STEP_GOAL] ?: dp.stepGoal,
                sleepGoalMinutes = p[Keys.SLEEP_GOAL] ?: dp.sleepGoalMinutes,
                saved = p[Keys.PROFILE_SAVED] ?: dp.saved,
            ),
            units = p[Keys.UNITS]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: defaults.units,
            themeId = p[Keys.THEME_ID] ?: defaults.themeId,
            darkMode = p[Keys.DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() } ?: defaults.darkMode,
            amoled = p[Keys.AMOLED] ?: defaults.amoled,
        )
    }

    suspend fun setUnits(value: Units) = context.dataStore.edit { it[Keys.UNITS] = value.name }
    suspend fun setThemeId(value: String) = context.dataStore.edit { it[Keys.THEME_ID] = value }
    suspend fun setDarkMode(value: DarkMode) = context.dataStore.edit { it[Keys.DARK_MODE] = value.name }
    suspend fun setAmoled(value: Boolean) = context.dataStore.edit { it[Keys.AMOLED] = value }

    suspend fun current(): Settings = settings.first()

    suspend fun setPassword(value: String) = context.dataStore.edit { it[Keys.PASSWORD] = value }
    suspend fun setUse24Hour(value: Boolean) = context.dataStore.edit { it[Keys.USE_24H] = value }
    suspend fun setConfirmOnWatch(value: Boolean) = context.dataStore.edit { it[Keys.CONFIRM_ON_WATCH] = value }
    suspend fun setRawLogging(value: Boolean) = context.dataStore.edit { it[Keys.RAW_LOGGING] = value }
    suspend fun setSdkLogging(value: Boolean) = context.dataStore.edit { it[Keys.SDK_LOGGING] = value }
    suspend fun setKeepConnected(value: Boolean) = context.dataStore.edit { it[Keys.KEEP_CONNECTED] = value }
    suspend fun setAutoSyncMinutes(value: Int) = context.dataStore.edit { it[Keys.AUTO_SYNC_MINUTES] = value }
    suspend fun setForwardNotifications(value: Boolean) = context.dataStore.edit { it[Keys.FORWARD_NOTIFICATIONS] = value }
    suspend fun setHealthConnectAutoExport(value: Boolean) = context.dataStore.edit { it[Keys.HC_AUTO_EXPORT] = value }

    suspend fun setAppMuted(pkg: String, muted: Boolean) = context.dataStore.edit {
        val now = it[Keys.MUTED_APPS] ?: emptySet()
        it[Keys.MUTED_APPS] = if (muted) now + pkg else now - pkg
    }

    suspend fun setProfile(p: Profile) = context.dataStore.edit {
        it[Keys.HEIGHT] = p.heightCm
        it[Keys.WEIGHT] = p.weightKg
        it[Keys.AGE] = p.age
        it[Keys.SEX] = p.sex.name
        it[Keys.STEP_GOAL] = p.stepGoal
        it[Keys.SLEEP_GOAL] = p.sleepGoalMinutes
        it[Keys.PROFILE_SAVED] = true
    }
}
