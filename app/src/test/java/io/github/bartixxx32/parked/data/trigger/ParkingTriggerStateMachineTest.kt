package io.github.bartixxx32.parked.data.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingTriggerStateMachineTest {

    private val machine = ParkingTriggerStateMachine()
    private val device = "AA:BB:CC:DD:EE:FF"
    private val delay = 15_000L
    private val start = 1_000_000L

    private fun connect(state: TriggerState = TriggerState(), now: Long = start): TriggerTransition =
        machine.onConnectionObserved(state, connected = true, deviceAddress = device, nowEpochMs = now, saveDelayMs = delay)

    private fun disconnect(state: TriggerState, now: Long = start + 1_000L): TriggerTransition =
        machine.onConnectionObserved(state, connected = false, deviceAddress = device, nowEpochMs = now, saveDelayMs = delay)

    private fun timer(state: TriggerState, now: Long): TriggerTransition =
        machine.onTimerElapsed(state, now)

    private fun scheduledAt(transition: TriggerTransition): Long =
        transition.actions.filterIsInstance<TriggerAction.ScheduleSave>().single().atEpochMs

    private fun captureAction(transition: TriggerTransition): TriggerAction.CaptureLocation? =
        transition.actions.filterIsInstance<TriggerAction.CaptureLocation>().singleOrNull()

    @Test
    fun `disconnect without previous connection never schedules a save`() {
        val transition = disconnect(TriggerState(), now = start + 5_000L)

        assertTrue(transition.actions.none { it is TriggerAction.ScheduleSave })
        assertEquals(TriggerPhase.UNARMED, transition.state.phase)
    }

    @Test
    fun `connect arms exactly one new cycle`() {
        val transition = connect()

        assertEquals(TriggerPhase.CONNECTED, transition.state.phase)
        assertEquals(1L, transition.state.cycleId)
        assertEquals(device, transition.state.deviceAddress)
        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
    }

    @Test
    fun `disconnect after connection schedules save after configured delay`() {
        val connected = connect()
        val transition = disconnect(connected.state)

        assertEquals(TriggerPhase.PENDING, transition.state.phase)
        assertEquals(start + 1_000L + delay, scheduledAt(transition))
        assertEquals(1L, transition.state.cycleId)
    }

    @Test
    fun `repeated connect events do not create extra cycles`() {
        val first = connect()
        val second = connect(first.state, now = start + 2_000L)

        assertEquals(first.state, second.state)
        assertTrue(second.actions.isEmpty())
    }

    @Test
    fun `repeated disconnect events do not schedule duplicate saves`() {
        val pending = disconnect(connect().state)
        val repeated = disconnect(pending.state, now = start + 2_000L)

        assertEquals(pending.state, repeated.state)
        assertTrue(repeated.actions.none { it is TriggerAction.ScheduleSave })
    }

    @Test
    fun `reconnect during pending delay cancels the save`() {
        val pending = disconnect(connect().state)
        val reconnected = connect(pending.state, now = start + 5_000L)

        assertEquals(TriggerPhase.CONNECTED, reconnected.state.phase)
        assertEquals(2L, reconnected.state.cycleId)
        assertTrue(reconnected.actions.contains(TriggerAction.CancelScheduledSave))
    }

    @Test
    fun `timer before deadline does nothing`() {
        val pending = disconnect(connect().state)
        val transition = timer(pending.state, now = pending.state.pendingAtEpochMs - 1L)

        assertEquals(TriggerPhase.PENDING, transition.state.phase)
        assertTrue(transition.actions.isEmpty())
    }

    @Test
    fun `timer at deadline starts capture once`() {
        val pending = disconnect(connect().state)
        val transition = timer(pending.state, now = pending.state.pendingAtEpochMs)

        assertEquals(TriggerPhase.CAPTURING, transition.state.phase)
        assertEquals(1L, captureAction(transition)?.cycleId)
        assertEquals(TriggerPhase.CAPTURING, transition.actions.filterIsInstance<TriggerAction.NotifyPhase>().single().phase)
    }

    @Test
    fun `duplicate timer events do not start a second capture`() {
        val pending = disconnect(connect().state)
        val capturing = timer(pending.state, now = pending.state.pendingAtEpochMs)
        val duplicate = timer(capturing.state, now = pending.state.pendingAtEpochMs + 2_000L)

        assertTrue(duplicate.actions.none { it is TriggerAction.CaptureLocation })
        assertEquals(TriggerPhase.CAPTURING, duplicate.state.phase)
    }

    @Test
    fun `successful capture marks the cycle saved`() {
        val pending = disconnect(connect().state)
        val capturing = timer(pending.state, now = pending.state.pendingAtEpochMs)
        val finished = machine.onCaptureFinished(capturing.state, saved = true, nowEpochMs = start + 30_000L)

        assertEquals(TriggerPhase.SAVED, finished.state.phase)
        assertEquals(start + 30_000L, finished.state.lastAutoSaveAtEpochMs)
        assertEquals(1L, finished.state.cycleId)
    }

    @Test
    fun `disconnect after a completed save is ignored until reconnect`() {
        val pending = disconnect(connect().state)
        var state = timer(pending.state, now = pending.state.pendingAtEpochMs).state
        state = machine.onCaptureFinished(state, saved = true, nowEpochMs = start + 30_000L).state

        val transition = disconnect(state, now = start + 40_000L)

        assertEquals(TriggerPhase.SAVED, transition.state.phase)
        assertTrue(transition.actions.none { it is TriggerAction.ScheduleSave })
    }

    @Test
    fun `a new connect disconnect cycle saves again after minimum interval`() {
        val firstPending = disconnect(connect().state)
        var state = timer(firstPending.state, now = firstPending.state.pendingAtEpochMs).state
        state = machine.onCaptureFinished(state, saved = true, nowEpochMs = start + 30_000L).state

        val reconnectAt = start + 10 * 60_000L
        state = connect(state, now = reconnectAt).state
        val secondPending = disconnect(state, now = reconnectAt + 1_000L)
        val transition = timer(secondPending.state, now = secondPending.state.pendingAtEpochMs + 1_000L)

        assertTrue(transition.actions.any { it is TriggerAction.CaptureLocation })
        assertEquals(2L, transition.state.cycleId)
    }

    @Test
    fun `flapping radio within minimum interval is suppressed`() {
        val firstPending = disconnect(connect().state)
        var state = timer(firstPending.state, now = firstPending.state.pendingAtEpochMs).state
        state = machine.onCaptureFinished(state, saved = true, nowEpochMs = start + 30_000L).state

        val reconnectAt = start + 40_000L
        state = connect(state, now = reconnectAt).state
        val secondPending = disconnect(state, now = reconnectAt + 1_000L)
        val transition = timer(secondPending.state, now = secondPending.state.pendingAtEpochMs)

        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
        assertTrue(transition.actions.any { it is TriggerAction.SuppressDuplicate })
        assertEquals(TriggerPhase.SAVED, transition.state.phase)
    }

    @Test
    fun `service restart while pending resumes the original deadline`() {
        val pending = disconnect(connect().state)
        val transition = machine.onServiceStarted(
            state = pending.state,
            connected = false,
            deviceAddress = device,
            nowEpochMs = start + 3_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.PENDING, transition.state.phase)
        assertEquals(pending.state.pendingAtEpochMs, scheduledAt(transition))
    }

    @Test
    fun `service restart after pending deadline captures immediately`() {
        val pending = disconnect(connect().state)
        val transition = machine.onServiceStarted(
            state = pending.state,
            connected = false,
            deviceAddress = device,
            nowEpochMs = pending.state.pendingAtEpochMs + 2_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.CAPTURING, transition.state.phase)
        assertEquals(1L, captureAction(transition)?.cycleId)
    }

    @Test
    fun `service restart with ancient pending save discards it`() {
        val pending = disconnect(connect().state)
        val transition = machine.onServiceStarted(
            state = pending.state,
            connected = false,
            deviceAddress = device,
            nowEpochMs = pending.state.pendingAtEpochMs + 11 * 60_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.UNARMED, transition.state.phase)
        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
    }

    @Test
    fun `service restart while disconnected after a save never saves by itself`() {
        var state = disconnect(connect().state).state
        state = timer(state, now = state.pendingAtEpochMs).state
        state = machine.onCaptureFinished(state, saved = true, nowEpochMs = start + 30_000L).state

        val transition = machine.onServiceStarted(
            state = state,
            connected = false,
            deviceAddress = device,
            nowEpochMs = start + 3_600_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.SAVED, transition.state.phase)
        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
    }

    @Test
    fun `service restart while connected keeps the monitored cycle armed`() {
        val connected = connect()
        val transition = machine.onServiceStarted(
            state = connected.state,
            connected = true,
            deviceAddress = device,
            nowEpochMs = start + 3_600_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.CONNECTED, transition.state.phase)
        assertEquals(1L, transition.state.cycleId)
    }

    @Test
    fun `service restart when persisted state says connected but radio is gone does not save`() {
        val connected = connect()
        val transition = machine.onServiceStarted(
            state = connected.state,
            connected = false,
            deviceAddress = device,
            nowEpochMs = start + 3_600_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.UNARMED, transition.state.phase)
        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
    }

    @Test
    fun `service restart during capture retries within the resume window`() {
        val pending = disconnect(connect().state)
        val capturing = timer(pending.state, now = pending.state.pendingAtEpochMs).state
        val transition = machine.onServiceStarted(
            state = capturing,
            connected = false,
            deviceAddress = device,
            nowEpochMs = pending.state.pendingAtEpochMs + 3_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.CAPTURING, transition.state.phase)
        assertEquals(1L, captureAction(transition)?.cycleId)
    }

    @Test
    fun `changing delay while pending reschedules from now`() {
        val pending = disconnect(connect().state)
        val transition = machine.onDelayChanged(
            state = pending.state,
            nowEpochMs = start + 5_000L,
            saveDelayMs = 30_000L,
        )

        assertEquals(start + 5_000L + 30_000L, transition.state.pendingAtEpochMs)
        assertEquals(start + 5_000L + 30_000L, scheduledAt(transition))
    }

    @Test
    fun `changing the selected device cancels pending work`() {
        val pending = disconnect(connect().state)
        val transition = machine.onConnectionObserved(
            state = pending.state,
            connected = false,
            deviceAddress = "11:22:33:44:55:66",
            nowEpochMs = start + 2_000L,
            saveDelayMs = delay,
        )

        assertEquals(TriggerPhase.UNARMED, transition.state.phase)
        assertEquals("11:22:33:44:55:66", transition.state.deviceAddress)
        assertTrue(transition.actions.contains(TriggerAction.CancelScheduledSave))
        assertTrue(transition.actions.none { it is TriggerAction.CaptureLocation })
    }

    @Test
    fun `disconnect during capture keeps capture running`() {
        val pending = disconnect(connect().state)
        val capturing = timer(pending.state, now = pending.state.pendingAtEpochMs)
        val repeated = disconnect(capturing.state, now = pending.state.pendingAtEpochMs + 1_000L)

        assertEquals(TriggerPhase.CAPTURING, repeated.state.phase)
        assertFalse(repeated.actions.any { it is TriggerAction.ScheduleSave })
    }

    @Test
    fun `stopping monitoring clears the armed state`() {
        val pending = disconnect(connect().state)
        val transition = machine.onMonitoringStopped(pending.state)

        assertEquals(TriggerPhase.UNARMED, transition.state.phase)
        assertTrue(transition.actions.contains(TriggerAction.CancelScheduledSave))
        assertTrue(transition.actions.contains(TriggerAction.CancelCapture))
    }
}
