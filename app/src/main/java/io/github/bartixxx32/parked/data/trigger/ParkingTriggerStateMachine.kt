package io.github.bartixxx32.parked.data.trigger

enum class TriggerPhase {
    UNARMED,
    CONNECTED,
    PENDING,
    CAPTURING,
    SAVED,
}

/**
 * Small, serialisable state machine state. It is persisted after every transition so a
 * foreground-service restart does not lose a pending automatic save.
 */
data class TriggerState(
    val phase: TriggerPhase = TriggerPhase.UNARMED,
    val cycleId: Long = 0L,
    val deviceAddress: String? = null,
    val pendingAtEpochMs: Long = 0L,
    val lastAutoSaveAtEpochMs: Long = 0L,
)

sealed interface TriggerAction {
    data class ScheduleSave(val atEpochMs: Long) : TriggerAction
    data object CancelScheduledSave : TriggerAction
    data object CancelCapture : TriggerAction
    data class CaptureLocation(
        val cycleId: Long,
        val deviceAddress: String?,
    ) : TriggerAction

    data class NotifyPhase(
        val phase: TriggerPhase,
        val cycleId: Long,
    ) : TriggerAction

    data class SuppressDuplicate(val lastAutoSaveAtEpochMs: Long) : TriggerAction
}

data class TriggerTransition(
    val state: TriggerState,
    val actions: List<TriggerAction> = emptyList(),
)

/**
 * Pure logic for deciding when an automatic parking save should happen.
 *
 * Rules:
 *  - only a CONNECTED -> DISCONNECTED transition arms a save;
 *  - repeated connection/disconnection broadcasts are idempotent;
 *  - reconnecting during the delay cancels the pending save;
 *  - at most one save per connection cycle (cycleId is unique in the database);
 *  - a pending save is resumed after a service restart, but only within a short window;
 *  - starting the service while the car is already disconnected never saves by itself;
 *  - two saves are separated by at least [minAutoSaveIntervalMs] to survive flapping radios.
 */
class ParkingTriggerStateMachine(
    private val minAutoSaveIntervalMs: Long = DEFAULT_MIN_AUTO_SAVE_INTERVAL_MS,
    private val maxPendingResumeMs: Long = DEFAULT_MAX_PENDING_RESUME_MS,
) {

    fun onConnectionObserved(
        state: TriggerState,
        connected: Boolean,
        deviceAddress: String,
        nowEpochMs: Long,
        saveDelayMs: Long,
    ): TriggerTransition {
        if (deviceAddress.isBlank()) return TriggerTransition(state)

        var current = state
        val actions = mutableListOf<TriggerAction>()

        if (!current.deviceAddress.isNullOrBlank() && current.deviceAddress != deviceAddress) {
            // A different radio was selected. Never carry a pending save across devices.
            current = TriggerState(
                phase = TriggerPhase.UNARMED,
                cycleId = current.cycleId,
                deviceAddress = deviceAddress,
                lastAutoSaveAtEpochMs = current.lastAutoSaveAtEpochMs,
            )
            actions += TriggerAction.CancelScheduledSave
            actions += TriggerAction.CancelCapture
        } else if (current.deviceAddress.isNullOrBlank()) {
            current = current.copy(deviceAddress = deviceAddress)
        }

        if (connected) {
            if (current.phase == TriggerPhase.CONNECTED && current.deviceAddress == deviceAddress) {
                return TriggerTransition(state, actions)
            }
            val previousPhase = current.phase
            current = current.copy(
                phase = TriggerPhase.CONNECTED,
                cycleId = current.cycleId + 1,
                deviceAddress = deviceAddress,
                pendingAtEpochMs = 0L,
            )
            if (previousPhase == TriggerPhase.PENDING) {
                actions += TriggerAction.CancelScheduledSave
            }
            if (previousPhase == TriggerPhase.CAPTURING) {
                actions += TriggerAction.CancelCapture
            }
            actions += TriggerAction.NotifyPhase(TriggerPhase.CONNECTED, current.cycleId)
            return TriggerTransition(current, actions)
        }

        if (current.phase == TriggerPhase.CONNECTED && current.deviceAddress == deviceAddress) {
            val pendingAt = nowEpochMs + saveDelayMs.coerceAtLeast(0L)
            current = current.copy(
                phase = TriggerPhase.PENDING,
                pendingAtEpochMs = pendingAt,
            )
            actions += TriggerAction.ScheduleSave(pendingAt)
            actions += TriggerAction.NotifyPhase(TriggerPhase.PENDING, current.cycleId)
            return TriggerTransition(current, actions)
        }

        // Repeated DISCONNECTED events, or events after a completed save, are ignored.
        return TriggerTransition(current, actions)
    }

    fun onServiceStarted(
        state: TriggerState,
        connected: Boolean,
        deviceAddress: String,
        nowEpochMs: Long,
        saveDelayMs: Long,
    ): TriggerTransition {
        if (deviceAddress.isBlank()) return TriggerTransition(state)

        var current = state
        val actions = mutableListOf<TriggerAction>()

        if (!current.deviceAddress.isNullOrBlank() && current.deviceAddress != deviceAddress) {
            current = TriggerState(deviceAddress = deviceAddress)
            actions += TriggerAction.CancelScheduledSave
            actions += TriggerAction.CancelCapture
        } else if (current.deviceAddress.isNullOrBlank()) {
            current = current.copy(deviceAddress = deviceAddress)
        }

        if (connected) {
            val transition = onConnectionObserved(
                state = current,
                connected = true,
                deviceAddress = deviceAddress,
                nowEpochMs = nowEpochMs,
                saveDelayMs = saveDelayMs,
            )
            return TriggerTransition(transition.state, actions + transition.actions)
        }

        return when (current.phase) {
            TriggerPhase.PENDING -> {
                val elapsed = nowEpochMs - current.pendingAtEpochMs
                when {
                    nowEpochMs < current.pendingAtEpochMs -> TriggerTransition(
                        current,
                        actions +
                            TriggerAction.ScheduleSave(current.pendingAtEpochMs) +
                            TriggerAction.NotifyPhase(TriggerPhase.PENDING, current.cycleId),
                    )

                    elapsed <= maxPendingResumeMs -> TriggerTransition(
                        current.copy(phase = TriggerPhase.CAPTURING),
                        actions +
                            TriggerAction.CaptureLocation(current.cycleId, current.deviceAddress) +
                            TriggerAction.NotifyPhase(TriggerPhase.CAPTURING, current.cycleId),
                    )

                    else -> TriggerTransition(
                        current.copy(phase = TriggerPhase.UNARMED, pendingAtEpochMs = 0L),
                        actions + TriggerAction.NotifyPhase(TriggerPhase.UNARMED, current.cycleId),
                    )
                }
            }

            TriggerPhase.CAPTURING -> {
                val captureAge = if (current.pendingAtEpochMs > 0L) {
                    nowEpochMs - current.pendingAtEpochMs
                } else {
                    Long.MAX_VALUE
                }
                if (captureAge <= maxPendingResumeMs) {
                    TriggerTransition(
                        current,
                        actions +
                            TriggerAction.CaptureLocation(current.cycleId, current.deviceAddress) +
                            TriggerAction.NotifyPhase(TriggerPhase.CAPTURING, current.cycleId),
                    )
                } else {
                    TriggerTransition(
                        current.copy(phase = TriggerPhase.UNARMED, pendingAtEpochMs = 0L),
                        actions + TriggerAction.NotifyPhase(TriggerPhase.UNARMED, current.cycleId),
                    )
                }
            }

            // The service was dead when the disconnect happened. We cannot know how long ago
            // the car was parked, so never save a potentially wrong location: do not arm.
            TriggerPhase.CONNECTED -> TriggerTransition(
                current.copy(phase = TriggerPhase.UNARMED, pendingAtEpochMs = 0L),
                actions + TriggerAction.NotifyPhase(TriggerPhase.UNARMED, current.cycleId),
            )

            else -> TriggerTransition(current, actions)
        }
    }

    fun onDelayChanged(
        state: TriggerState,
        nowEpochMs: Long,
        saveDelayMs: Long,
    ): TriggerTransition {
        if (state.phase != TriggerPhase.PENDING) return TriggerTransition(state)
        val pendingAt = nowEpochMs + saveDelayMs.coerceAtLeast(0L)
        return TriggerTransition(
            state.copy(pendingAtEpochMs = pendingAt),
            listOf(
                TriggerAction.CancelScheduledSave,
                TriggerAction.ScheduleSave(pendingAt),
                TriggerAction.NotifyPhase(TriggerPhase.PENDING, state.cycleId),
            ),
        )
    }

    fun onTimerElapsed(
        state: TriggerState,
        nowEpochMs: Long,
    ): TriggerTransition {
        if (state.phase != TriggerPhase.PENDING) return TriggerTransition(state)
        if (nowEpochMs < state.pendingAtEpochMs) return TriggerTransition(state)

        val lastSave = state.lastAutoSaveAtEpochMs
        if (lastSave > 0L && nowEpochMs - lastSave < minAutoSaveIntervalMs) {
            val suppressed = state.copy(phase = TriggerPhase.SAVED, pendingAtEpochMs = 0L)
            return TriggerTransition(
                suppressed,
                listOf(
                    TriggerAction.SuppressDuplicate(lastSave),
                    TriggerAction.NotifyPhase(TriggerPhase.SAVED, state.cycleId),
                ),
            )
        }

        return TriggerTransition(
            state.copy(phase = TriggerPhase.CAPTURING),
            listOf(
                TriggerAction.CaptureLocation(state.cycleId, state.deviceAddress),
                TriggerAction.NotifyPhase(TriggerPhase.CAPTURING, state.cycleId),
            ),
        )
    }

    fun onCaptureFinished(
        state: TriggerState,
        saved: Boolean,
        nowEpochMs: Long,
    ): TriggerTransition {
        if (state.phase != TriggerPhase.CAPTURING) return TriggerTransition(state)
        val nextPhase = if (saved) TriggerPhase.SAVED else TriggerPhase.UNARMED
        val finished = state.copy(
            phase = nextPhase,
            pendingAtEpochMs = 0L,
            lastAutoSaveAtEpochMs = if (saved) nowEpochMs else state.lastAutoSaveAtEpochMs,
        )
        return TriggerTransition(
            finished,
            listOf(TriggerAction.NotifyPhase(nextPhase, state.cycleId)),
        )
    }

    fun onMonitoringStopped(state: TriggerState): TriggerTransition = TriggerTransition(
        TriggerState(deviceAddress = state.deviceAddress),
        listOf(
            TriggerAction.CancelScheduledSave,
            TriggerAction.CancelCapture,
            TriggerAction.NotifyPhase(TriggerPhase.UNARMED, state.cycleId),
        ),
    )

    companion object {
        const val DEFAULT_MIN_AUTO_SAVE_INTERVAL_MS = 120_000L
        const val DEFAULT_MAX_PENDING_RESUME_MS = 10 * 60_000L
    }
}
