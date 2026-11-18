package com.bartixxx.parked.monitor

import com.bartixxx.parked.data.trigger.TriggerPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitorRuntimeState(
    val running: Boolean = false,
    val targetName: String? = null,
    val targetAddress: String? = null,
    val bluetoothEnabled: Boolean = false,
    val connected: Boolean = false,
    val phase: TriggerPhase = TriggerPhase.UNARMED,
    val cycleId: Long = 0L,
    val pendingAtEpochMs: Long = 0L,
    val lastSavedAtEpochMs: Long = 0L,
    val lastError: String? = null,
)

class MonitorStateHolder {
    private val _state = MutableStateFlow(MonitorRuntimeState())
    val state: StateFlow<MonitorRuntimeState> = _state.asStateFlow()

    fun update(transform: (MonitorRuntimeState) -> MonitorRuntimeState) {
        _state.update(transform)
    }

    fun reset() {
        _state.value = MonitorRuntimeState()
    }
}
