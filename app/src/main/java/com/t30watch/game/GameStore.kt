package com.t30watch.game

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only game state that is stored: which badges the user has already been shown.
 * Everything else (XP, levels, streaks, badges) is recomputed from the database.
 * Own SharedPreferences file ("game"), separate from AppSettings.
 */
@Singleton
class GameStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("game", Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(prefs.getStringSet(KEY_SEEN, null)?.toSet() ?: emptySet())
    /** Badge ids already celebrated. */
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    /** Badges celebrated since the app started. They keep their "NEW" marker until the process ends. */
    val fresh: StateFlow<Set<String>> = _fresh.asStateFlow()

    @Synchronized
    fun markSeen(ids: Collection<String>) {
        val add = ids.filterNot { it in _seen.value }
        if (add.isEmpty()) return
        val next = _seen.value + add
        prefs.edit().putStringSet(KEY_SEEN, HashSet(next)).apply()
        _seen.value = next
        _fresh.value = _fresh.value + add
    }

    private companion object {
        const val KEY_SEEN = "seen_badges"
    }
}
