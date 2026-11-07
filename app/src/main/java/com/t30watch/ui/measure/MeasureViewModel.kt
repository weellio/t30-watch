package com.t30watch.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.t30watch.data.MeasureRepository
import com.t30watch.data.WatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MeasureViewModel @Inject constructor(
    private val measure: MeasureRepository,
    watch: WatchRepository,
) : ViewModel() {
    val connection = watch.connection
    val heart = measure.heart
    val bp = measure.bp
    val recent = measure.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startHeart() = measure.startHeartRate()
    fun stopHeart() = measure.stopHeartRate()
    fun startBp() = measure.startBloodPressure()
    fun stopBp() = measure.stopBloodPressure()

    /** Leaving the screen ends any measurement, so the watch is never left measuring by itself. */
    override fun onCleared() {
        measure.stopAll()
    }
}
