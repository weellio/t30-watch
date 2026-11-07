package com.t30watch.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.settings.AppSettings
import com.t30watch.data.settings.Settings
import com.t30watch.data.settings.Units
import com.t30watch.game.Badge
import com.t30watch.game.GameRepository
import com.t30watch.game.GameState
import com.t30watch.game.GameStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RewardsViewModel @Inject constructor(
    repo: GameRepository,
    private val store: GameStore,
    settings: AppSettings,
) : ViewModel() {
    /** Null until the first computation finishes. */
    val state: StateFlow<GameState?> = repo.state

    val units: StateFlow<Units> = settings.settings.map { it.units }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings().units)

    /** Unlocked badges not celebrated yet. */
    val toCelebrate: StateFlow<List<Badge>> = combine(repo.state, store.seen) { s, seen ->
        s?.badges?.filter { it.unlocked && it.id !in seen }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Badge ids that get a "NEW" marker: not celebrated yet, or celebrated since the app started. */
    val newIds: StateFlow<Set<String>> = combine(repo.state, store.seen, store.fresh) { s, seen, fresh ->
        s?.badges?.filter { it.unlocked && (it.id !in seen || it.id in fresh) }?.map { it.id }?.toSet().orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun markSeen(badges: List<Badge>) = store.markSeen(badges.map { it.id })
}
