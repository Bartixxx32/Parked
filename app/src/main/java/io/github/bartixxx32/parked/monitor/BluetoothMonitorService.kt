package io.github.bartixxx32.parked.monitor

import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.bartixxx32.parked.AppContainer
import io.github.bartixxx32.parked.ParkedApp
import io.github.bartixxx32.parked.capture.AcquiredFix
import io.github.bartixxx32.parked.capture.AcquisitionOutcome
import io.github.bartixxx32.parked.capture.CaptureFailure
import io.github.bartixxx32.parked.capture.CaptureOutcome
import io.github.bartixxx32.parked.capture.CaptureRequest
import io.github.bartixxx32.parked.capture.CaptureTrigger
import io.github.bartixxx32.parked.capture.userMessage
import io.github.bartixxx32.parked.data.settings.AppSettings
import io.github.bartixxx32.parked.data.trigger.ParkingTriggerStateMachine
import io.github.bartixxx32.parked.data.trigger.TriggerAction
import io.github.bartixxx32.parked.data.trigger.TriggerPhase
import io.github.bartixxx32.parked.data.trigger.TriggerState
import io.github.bartixxx32.parked.data.trigger.TriggerTransition
import io.github.bartixxx32.parked.notifications.NotificationHelper
import io.github.bartixxx32.parked.util.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class BluetoothMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transitionMutex = Mutex()
    private val stateMachine = ParkingTriggerStateMachine()

    private lateinit var container: AppContainer

    @Volatile
    private var currentState = TriggerState()

    @Volatile
    private var settings: AppSettings? = null

    @Volatile
    private var lastObservedConnected: Boolean? = null

    private var initialized = false
    private var monitoringStarted = false
    private var foregroundStarted = false
    private var receiver: BroadcastReceiver? = null

    private var settingsJob: Job? = null
    private var pollJob: Job? = null
    private var timerJob: Job? = null
    private var captureJob: Job? = null
    private var prefetchJob: Job? = null
    private var prefetchDeferred: CompletableDeferred<PrefetchResult?>? = null
    private var prefetchCycleId: Long? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as ParkedApp).container
        isRunning = true
        container.monitorState.update { it.copy(running = true) }
        startInForeground("Uruchamianie monitorowania…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    runCatching { container.settingsRepository.setMonitoringEnabled(false) }
                    stopMonitoringInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_CANCEL_PENDING -> {
                serviceScope.launch {
                    cancelPendingWork()
                    container.diagnosticsRepository.info(
                        "Trigger",
                        "Użytkownik anulował oczekujący zapis",
                    )
                }
                return if (foregroundStarted) START_STICKY else START_NOT_STICKY
            }

            else -> {
                if (!foregroundStarted) return START_NOT_STICKY
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        container.diagnosticsRepository.info(
            "Service",
            "Zadanie usunięte z ostatnich aplikacji; usługa pierwszoplanowa nadal działa (jeśli system jej nie zabił)",
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        cancelPrefetch()
        serviceScope.cancel()
        unregisterBluetoothReceiver()
        container.bluetoothRepository.stopConnectionTracking()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        container.monitorState.update {
            it.copy(
                running = false,
                connected = false,
                phase = TriggerPhase.UNARMED,
                pendingAtEpochMs = 0L,
            )
        }
        container.diagnosticsRepository.info("Service", "Monitorowanie zatrzymane")
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = NotificationHelper.monitorNotification(this, text)
        val withLocationType = PermissionUtils.hasAnyLocation(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (withLocationType) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        foregroundStarted = try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.MONITOR_NOTIFICATION_ID,
                notification,
                types,
            )
            true
        } catch (error: Exception) {
            container.diagnosticsRepository.warn(
                "Service",
                "Nie udało się uruchomić FGS z typem lokalizacji: ${error.message}",
            )
            try {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                true
            } catch (fatal: Exception) {
                container.diagnosticsRepository.error(
                    "Service",
                    "Nie można uruchomić usługi pierwszoplanowej: ${fatal.message}",
                )
                stopSelf()
                false
            }
        }
    }

    private fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true
        registerBluetoothReceiver()
        container.bluetoothRepository.startConnectionTracking()

        pollJob = serviceScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (initialized) reconcile("cykliczne sprawdzenie")
            }
        }

        // Profile proxies bind asynchronously. Re-check shortly after start so an already
        // connected device is shown as connected without waiting for the 20 s poll.
        listOf(PROFILE_WARMUP_RECHECK_MS, PROFILE_WARMUP_RECHECK_MS * 3).forEach { delayMs ->
            serviceScope.launch {
                delay(delayMs)
                if (initialized) reconcile("rozgrzewka profili Bluetooth")
            }
        }

        settingsJob = serviceScope.launch {
            container.settingsRepository.settings.collect { newSettings ->
                val previous = settings
                settings = newSettings

                if (!newSettings.monitoringEnabled) {
                    stopMonitoringInternal()
                    stopSelf()
                    return@collect
                }

                ensureInitialized()

                if (previous != null && previous.deviceAddress != newSettings.deviceAddress) {
                    resetForDeviceChange(newSettings)
                }
                if (previous != null && previous.saveDelaySeconds != newSettings.saveDelaySeconds) {
                    process { state ->
                        stateMachine.onDelayChanged(
                            state = state,
                            nowEpochMs = now(),
                            saveDelayMs = newSettings.saveDelaySeconds * 1000L,
                        )
                    }
                }

                updateRuntime()
                refreshNotification()
                reconcile("ustawienia")
            }
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initialized = true
        val currentSettings = settings ?: return
        val persisted = runCatching { container.triggerStateRepository.current() }
            .getOrDefault(TriggerState())
        val address = currentSettings.deviceAddress
        // cycleId guards a unique index in Room. Seed it from every source that may have
        // survived a crash, so re-enabling monitoring can never look like a duplicate of
        // an older saved spot.
        val storedCycleId = runCatching { container.parkingRepository.maxCycleId() }
            .getOrDefault(0L)
        val seededCycleId = maxOf(persisted.cycleId, storedCycleId, currentState.cycleId)

        currentState = if (!address.isNullOrBlank() && persisted.deviceAddress == address) {
            persisted.copy(cycleId = seededCycleId)
        } else {
            TriggerState(deviceAddress = address, cycleId = seededCycleId)
        }
        if (persisted != currentState) {
            runCatching { container.triggerStateRepository.save(currentState) }
        }

        if (address.isNullOrBlank()) {
            updateRuntime(error = "Nie wybrano urządzenia Bluetooth")
            return
        }

        val connected = container.bluetoothRepository.isConnected(address)
        lastObservedConnected = connected
        process { state ->
            stateMachine.onServiceStarted(
                state = state,
                connected = connected,
                deviceAddress = address,
                nowEpochMs = now(),
                saveDelayMs = currentSettings.saveDelaySeconds * 1000L,
            )
        }
    }

    private suspend fun reconcile(reason: String) {
        val currentSettings = settings ?: return
        if (!currentSettings.monitoringEnabled) return

        val bluetooth = container.bluetoothRepository
        if (!bluetooth.hasBluetoothPermission()) {
            updateRuntime(error = "Brak uprawnienia Bluetooth. Otwórz aplikację i przyznaj dostęp.")
            return
        }
        if (!bluetooth.isBluetoothEnabled()) {
            if (currentState.phase != TriggerPhase.UNARMED || lastObservedConnected != null) {
                container.diagnosticsRepository.warn(
                    "Bluetooth",
                    "Bluetooth wyłączony — wstrzymano monitorowanie ($reason)",
                )
                process { state -> stateMachine.onMonitoringStopped(state) }
                lastObservedConnected = null
            }
            updateRuntime(error = "Bluetooth jest wyłączony")
            return
        }

        val address = currentSettings.deviceAddress
        if (address.isNullOrBlank()) {
            updateRuntime(error = "Nie wybrano urządzenia Bluetooth")
            return
        }

        val connected = bluetooth.isConnected(address)
        if (lastObservedConnected != null && lastObservedConnected != connected) {
            container.diagnosticsRepository.info(
                "Bluetooth",
                if (connected) {
                    "Połączono z ${currentSettings.deviceName ?: address}"
                } else {
                    "Rozłączono z ${currentSettings.deviceName ?: address}"
                },
            )
        }
        lastObservedConnected = connected
        updateRuntime()

        process { state ->
            stateMachine.onConnectionObserved(
                state = state,
                connected = connected,
                deviceAddress = address,
                nowEpochMs = now(),
                saveDelayMs = currentSettings.saveDelaySeconds * 1000L,
            )
        }
    }

    private suspend fun resetForDeviceChange(newSettings: AppSettings) {
        timerJob?.cancel()
        timerJob = null
        captureJob?.cancel()
        captureJob = null
        cancelPrefetch()
        transitionMutex.withLock {
            currentState = TriggerState(
                deviceAddress = newSettings.deviceAddress,
                cycleId = currentState.cycleId,
                lastAutoSaveAtEpochMs = currentState.lastAutoSaveAtEpochMs,
            )
            lastObservedConnected = null
            runCatching { container.triggerStateRepository.save(currentState) }
        }
        container.diagnosticsRepository.info(
            "Bluetooth",
            "Zmieniono urządzenie na ${newSettings.deviceName ?: newSettings.deviceAddress}",
        )
        updateRuntime()
    }

    private suspend fun process(transitionProvider: (TriggerState) -> TriggerTransition) {
        transitionMutex.withLock {
            val previousPhase = currentState.phase
            val transition = transitionProvider(currentState)
            val changed = transition.state != currentState
            currentState = transition.state
            if (changed) {
                runCatching { container.triggerStateRepository.save(currentState) }
            }
            updateRuntime()
            transition.actions.forEach { action -> handleAction(action) }
            // Leaving PENDING for anything other than CAPTURING means the cycle was
            // abandoned (reconnect, expiry, duplicate), so the fix captured at disconnect
            // must not leak into a later cycle. A delay change stays in PENDING and keeps it.
            if (previousPhase == TriggerPhase.PENDING &&
                currentState.phase != TriggerPhase.PENDING &&
                currentState.phase != TriggerPhase.CAPTURING
            ) {
                cancelPrefetch()
            }
        }
    }

    private fun handleAction(action: TriggerAction) {
        when (action) {
            is TriggerAction.ScheduleSave -> scheduleSave(action.atEpochMs)

            TriggerAction.CancelScheduledSave -> {
                timerJob?.cancel()
                timerJob = null
            }

            TriggerAction.CancelCapture -> {
                captureJob?.cancel()
                captureJob = null
                cancelPrefetch()
            }

            is TriggerAction.CaptureLocation -> {
                if (captureJob?.isActive == true) return
                captureJob = serviceScope.launch {
                    performCapture(
                        cycleId = action.cycleId,
                        deviceAddress = action.deviceAddress,
                    )
                }
            }

            is TriggerAction.NotifyPhase -> {
                updateRuntime()
                refreshNotification()
            }

            is TriggerAction.SuppressDuplicate -> {
                cancelPrefetch()
                container.diagnosticsRepository.info(
                    "Trigger",
                    "Pominięto zapis, aby uniknąć duplikatu (ostatni zapis ${action.lastAutoSaveAtEpochMs} ms temu)",
                )
            }
        }
    }

    private fun scheduleSave(atEpochMs: Long) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            val remaining = atEpochMs - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            process { state ->
                stateMachine.onTimerElapsed(state, now())
            }
        }
        // Acquire the fix immediately, while the phone is still next to the car. Waiting
        // out the reconnect delay first would record where the user walked to, not where
        // the car was parked. The fix is kept in memory and persisted at the deadline.
        startPrefetch()
    }

    /**
     * Starts a background acquisition for the current pending cycle. Safe to call several
     * times: an in-flight acquisition for the same cycle is reused, and a new cycle
     * replaces the previous one.
     */
    private fun startPrefetch() {
        val currentSettings = settings ?: return
        if (!currentSettings.monitoringEnabled) return
        val cycleId = currentState.cycleId
        // Reuse a still-running or already-finished acquisition for the same cycle: a
        // delay change must not move the point that was captured at disconnect.
        if (prefetchDeferred != null && prefetchCycleId == cycleId) return

        cancelPrefetch()
        val deferred = CompletableDeferred<PrefetchResult?>()
        prefetchDeferred = deferred
        prefetchCycleId = cycleId
        prefetchJob = serviceScope.launch {
            val wakeLock = acquireWakeLock()
            try {
                container.diagnosticsRepository.info(
                    "GPS",
                    "Pobieranie lokalizacji przy rozłączeniu (cykl $cycleId)",
                )
                val prefetchSettings = currentSettings.copy(
                    gpsTimeoutSeconds = currentSettings.gpsTimeoutSeconds
                        .coerceAtMost(PREFETCH_MAX_SECONDS),
                )
                val result = when (
                    val acquisition = container.captureEngine.acquire(
                        settings = prefetchSettings,
                        attempts = 1,
                    )
                ) {
                    is AcquisitionOutcome.Acquired -> {
                        container.diagnosticsRepository.info(
                            "GPS",
                            "Lokalizacja gotowa przy rozłączeniu: dokładność " +
                                "${acquisition.fix.accuracyMeters.toInt()} m" +
                                (acquisition.fix.provider?.let { ", źródło $it" } ?: ""),
                        )
                        PrefetchResult(acquisition.fix, null, null)
                    }

                    is AcquisitionOutcome.Failed -> {
                        container.diagnosticsRepository.warn(
                            "GPS",
                            "Nie udało się pobrać lokalizacji przy rozłączeniu: " +
                                "${acquisition.reason.userMessage()} ${acquisition.detail ?: ""}".trim(),
                        )
                        PrefetchResult(null, acquisition.reason, acquisition.detail)
                    }
                }
                deferred.complete(result)
            } catch (cancelled: CancellationException) {
                deferred.complete(null)
                throw cancelled
            } catch (error: Exception) {
                deferred.complete(
                    PrefetchResult(null, CaptureFailure.INTERNAL_ERROR, error.message),
                )
            } finally {
                releaseWakeLock(wakeLock)
            }
        }
    }

    private fun cancelPrefetch() {
        val deferred = prefetchDeferred
        val job = prefetchJob
        prefetchDeferred = null
        prefetchJob = null
        prefetchCycleId = null
        deferred?.complete(null)
        job?.cancel()
    }

    /** Forgets a finished prefetch without cancelling a newer one. */
    private fun clearPrefetch(cycleId: Long) {
        if (prefetchCycleId != cycleId) return
        val deferred = prefetchDeferred
        val job = prefetchJob
        prefetchDeferred = null
        prefetchJob = null
        prefetchCycleId = null
        deferred?.complete(null)
        job?.cancel()
    }

    private suspend fun awaitPrefetch(
        cycleId: Long,
        currentSettings: AppSettings,
    ): PrefetchResult? {
        val deferred = prefetchDeferred
        if (deferred == null || prefetchCycleId != cycleId) return null
        // The engine enforces its own timeouts; this is only a last-resort guard so a
        // misbehaving provider can never block the save forever.
        val waitMs = currentSettings.gpsTimeoutSeconds
            .coerceAtMost(PREFETCH_MAX_SECONDS) * 1000L + PREFETCH_GRACE_MS
        return withTimeoutOrNull(waitMs) { deferred.await() }
    }

    private suspend fun performCapture(cycleId: Long, deviceAddress: String?) {
        val currentSettings = settings ?: return
        if (!currentSettings.monitoringEnabled) return

        container.diagnosticsRepository.info("GPS", "Zapis lokalizacji dla cyklu $cycleId")
        refreshNotification()

        val wakeLock = acquireWakeLock()
        try {
            val request = CaptureRequest(
                trigger = CaptureTrigger.AUTO,
                cycleId = cycleId,
                deviceAddress = deviceAddress,
                deviceName = currentSettings.deviceName,
            )
            val prefetched = awaitPrefetch(cycleId, currentSettings)
            val outcome = when {
                prefetched?.fix != null -> {
                    container.diagnosticsRepository.info(
                        "GPS",
                        "Zapisuję punkt pobrany w chwili rozłączenia",
                    )
                    container.captureEngine.save(request, prefetched.fix)
                }

                prefetched != null -> CaptureOutcome.Failed(
                    reason = prefetched.failure ?: CaptureFailure.NO_LOCATION,
                    detail = prefetched.detail,
                )

                else -> {
                    container.diagnosticsRepository.info(
                        "GPS",
                        "Brak punktu z chwili rozłączenia — pobieram lokalizację teraz",
                    )
                    container.captureEngine.capture(request, currentSettings)
                }
            }
            when (outcome) {
                is CaptureOutcome.Saved -> {
                    process { state ->
                        stateMachine.onCaptureFinished(state, saved = true, nowEpochMs = now())
                    }
                    container.diagnosticsRepository.info(
                        "Zapis",
                        "Zapisano miejsce (dokładność ${outcome.spot.accuracyMeters?.toInt() ?: "?"} m" +
                            if (outcome.usedFallback) ", punkt awaryjny)" else ")",
                    )
                }

                CaptureOutcome.Duplicate -> {
                    process { state ->
                        stateMachine.onCaptureFinished(state, saved = true, nowEpochMs = now())
                    }
                    container.diagnosticsRepository.info(
                        "Zapis",
                        "Cykl $cycleId już zapisany — pominięto duplikat",
                    )
                }

                is CaptureOutcome.Failed -> {
                    process { state ->
                        stateMachine.onCaptureFinished(state, saved = false, nowEpochMs = now())
                    }
                    container.diagnosticsRepository.error(
                        "GPS",
                        "Nie zapisano lokalizacji: ${outcome.reason.userMessage()} ${outcome.detail ?: ""}".trim(),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            container.diagnosticsRepository.info(
                "GPS",
                "Pobieranie lokalizacji anulowane (radio połączone ponownie lub monitorowanie zatrzymane)",
            )
            throw cancelled
        } catch (error: Exception) {
            process { state ->
                stateMachine.onCaptureFinished(state, saved = false, nowEpochMs = now())
            }
            container.diagnosticsRepository.error("GPS", "Nieoczekiwany błąd: ${error.message}")
        } finally {
            clearPrefetch(cycleId)
            releaseWakeLock(wakeLock)
        }
    }

    private fun updateRuntime(error: String? = null) {
        val currentSettings = settings
        container.monitorState.update { previous ->
            previous.copy(
                running = true,
                targetName = currentSettings?.deviceName,
                targetAddress = currentSettings?.deviceAddress,
                bluetoothEnabled = container.bluetoothRepository.isBluetoothEnabled(),
                connected = lastObservedConnected == true,
                phase = currentState.phase,
                cycleId = currentState.cycleId,
                pendingAtEpochMs = currentState.pendingAtEpochMs,
                lastSavedAtEpochMs = maxOf(
                    previous.lastSavedAtEpochMs,
                    currentState.lastAutoSaveAtEpochMs,
                ),
                lastError = error,
            )
        }
    }

    private fun refreshNotification() {
        val currentSettings = settings
        val name = currentSettings?.deviceName ?: "radio samochodowe"
        val text = when (currentState.phase) {
            TriggerPhase.UNARMED -> if (currentSettings?.deviceAddress.isNullOrBlank()) {
                "Wybierz radio samochodowe w ustawieniach aplikacji."
            } else {
                "Oczekuję na połączenie z $name."
            }

            TriggerPhase.CONNECTED -> "Połączono z $name. Wykrywanie rozłączenia aktywne."
            TriggerPhase.PENDING -> {
                val seconds = ((currentState.pendingAtEpochMs - now()) / 1000L)
                    .coerceAtLeast(1L)
                "Rozłączono z $name. Zapis lokalizacji za ok. $seconds s."
            }

            TriggerPhase.CAPTURING -> "Zapisywanie miejsca…"
            TriggerPhase.SAVED -> "Miejsce zapisane. Oczekuję na ponowne połączenie z $name."
        }
        runCatching { NotificationHelper.updateMonitorNotification(this, text) }
    }

    private fun registerBluetoothReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
            }
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                val selected = settings?.deviceAddress
                if (selected != null && device != null &&
                    !device.address.equals(selected, ignoreCase = true)
                ) {
                    return
                }
                serviceScope.launch { reconcile("broadcast: $action") }
            }
        }
        try {
            ContextCompat.registerReceiver(
                this,
                newReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = newReceiver
        } catch (error: Exception) {
            container.diagnosticsRepository.error(
                "Service",
                "Nie można zarejestrować odbiornika Bluetooth: ${error.message}",
            )
        }
    }

    private fun unregisterBluetoothReceiver() {
        receiver?.let { current ->
            runCatching { unregisterReceiver(current) }
        }
        receiver = null
    }

    private suspend fun stopMonitoringInternal() {
        timerJob?.cancel()
        timerJob = null
        captureJob?.cancel()
        captureJob = null
        cancelPrefetch()
        transitionMutex.withLock {
            currentState = stateMachine.onMonitoringStopped(currentState).state
            runCatching { container.triggerStateRepository.save(currentState) }
        }
        unregisterBluetoothReceiver()
        container.bluetoothRepository.stopConnectionTracking()
        runCatching { NotificationManagerCompat.from(this).cancel(NotificationHelper.MONITOR_NOTIFICATION_ID) }
    }

    private suspend fun cancelPendingWork() {
        process { state -> stateMachine.onMonitoringStopped(state) }
        updateRuntime()
        refreshNotification()
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val powerManager = getSystemService(PowerManager::class.java)
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Parked:locationCapture",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_CAPTURE_WAKE_LOCK_MS)
        }
    }.getOrNull()

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val ACTION_START = "io.github.bartixxx32.parked.action.START"
        const val ACTION_STOP = "io.github.bartixxx32.parked.action.STOP"
        const val ACTION_CANCEL_PENDING = "io.github.bartixxx32.parked.action.CANCEL_PENDING"

        private const val POLL_INTERVAL_MS = 20_000L
        private const val PROFILE_WARMUP_RECHECK_MS = 1_500L
        private const val PREFETCH_MAX_SECONDS = 20
        private const val PREFETCH_GRACE_MS = 5_000L
        private const val MAX_CAPTURE_WAKE_LOCK_MS = 3 * 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BluetoothMonitorService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelPending(context: Context) {
            if (!isRunning) return
            runCatching {
                val intent = Intent(context, BluetoothMonitorService::class.java)
                    .setAction(ACTION_CANCEL_PENDING)
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BluetoothMonitorService::class.java))
            }
        }
    }
}

/**
 * Result of the disconnect-time acquisition. A null [fix] with a [failure] means the
 * acquisition finished and genuinely failed, so there is no point retrying immediately.
 */
private data class PrefetchResult(
    val fix: AcquiredFix?,
    val failure: CaptureFailure?,
    val detail: String?,
)
