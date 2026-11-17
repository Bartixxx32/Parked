package io.github.bartixxx32.parked

import android.app.Application
import io.github.bartixxx32.parked.monitor.MonitorWatchdogWorker

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
