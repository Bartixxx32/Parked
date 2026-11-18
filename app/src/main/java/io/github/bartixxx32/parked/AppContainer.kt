package io.github.bartixxx32.parked

import android.content.Context
import androidx.room.Room
import io.github.bartixxx32.parked.bluetooth.BluetoothRepository
import io.github.bartixxx32.parked.capture.ParkingCaptureEngine
import io.github.bartixxx32.parked.data.DiagnosticsRepository
import io.github.bartixxx32.parked.data.ParkingRepository
import io.github.bartixxx32.parked.data.db.AppDatabase
import io.github.bartixxx32.parked.data.settings.SettingsRepository
import io.github.bartixxx32.parked.data.trigger.TriggerStateRepository
import io.github.bartixxx32.parked.location.CompositeLocationSource
import io.github.bartixxx32.parked.location.LocationSource
import io.github.bartixxx32.parked.monitor.MonitorStateHolder
import io.github.bartixxx32.parked.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "parked.db").build()
    }

    val settingsRepository by lazy { SettingsRepository(appContext) }

    val triggerStateRepository by lazy { TriggerStateRepository(appContext) }

    val diagnosticsRepository by lazy {
        DiagnosticsRepository(database.diagnosticsEventDao(), applicationScope)
    }

    val parkingRepository by lazy {
        ParkingRepository(appContext, database.parkingSpotDao())
    }

    val bluetoothRepository by lazy { BluetoothRepository(appContext) }

    val locationSource: LocationSource by lazy {
        CompositeLocationSource(appContext) { message ->
            diagnosticsRepository.info("GPS", message)
        }
    }

    val captureEngine by lazy {
        ParkingCaptureEngine(locationSource, parkingRepository)
    }

    val monitorState = MonitorStateHolder()

    init {
        NotificationHelper.createChannels(appContext)
    }
}
