package com.bartixxx.parked

import android.content.Context
import androidx.room.Room
import com.bartixxx.parked.bluetooth.BluetoothRepository
import com.bartixxx.parked.capture.ParkingCaptureEngine
import com.bartixxx.parked.data.DiagnosticsRepository
import com.bartixxx.parked.data.ParkingRepository
import com.bartixxx.parked.data.db.AppDatabase
import com.bartixxx.parked.data.settings.SettingsRepository
import com.bartixxx.parked.data.trigger.TriggerStateRepository
import com.bartixxx.parked.location.LocationSource
import com.bartixxx.parked.location.PlatformLocationSource
import com.bartixxx.parked.monitor.MonitorStateHolder
import com.bartixxx.parked.notifications.NotificationHelper
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
        PlatformLocationSource(appContext)
    }

    val captureEngine by lazy {
        ParkingCaptureEngine(locationSource, parkingRepository)
    }

    val monitorState = MonitorStateHolder()

    init {
        NotificationHelper.createChannels(appContext)
    }
}
