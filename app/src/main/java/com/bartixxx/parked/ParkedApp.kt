package com.bartixxx.parked

import android.app.Application
import com.bartixxx.parked.monitor.MonitorWatchdogWorker

class ParkedApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching { MonitorWatchdogWorker.schedule(this) }
        container.diagnosticsRepository.info("App", "Aplikacja uruchomiona")
    }
}
