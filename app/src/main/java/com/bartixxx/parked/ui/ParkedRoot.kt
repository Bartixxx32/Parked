package com.bartixxx.parked.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.ui.screens.DashboardScreen
import com.bartixxx.parked.ui.screens.DiagnosticsScreen
import com.bartixxx.parked.ui.screens.HistoryScreen
import com.bartixxx.parked.ui.screens.MapCamera
import com.bartixxx.parked.ui.screens.MapCameraSaver
import com.bartixxx.parked.ui.screens.MapScreen
import com.bartixxx.parked.ui.screens.SettingsScreen
import com.bartixxx.parked.util.AppIntents
import com.bartixxx.parked.util.PermissionUtils
import kotlinx.coroutines.flow.collectLatest

private enum class ParkedTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Pulpit", Icons.Filled.Home),
    HISTORY("Historia", Icons.Filled.History),
    MAP("Mapa", Icons.Filled.Map),
    SETTINGS("Ustawienia", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostyka", Icons.Filled.BugReport),
}

@Composable
fun ParkedRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val spots by viewModel.spots.collectAsStateWithLifecycle()
    val latestSpot by viewModel.latestSpot.collectAsStateWithLifecycle()
    val monitor by viewModel.monitorState.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val bondedDevices by viewModel.bondedDevices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var permissions by remember { mutableStateOf(PermissionSnapshot.read(context)) }
    var tabName by rememberSaveable { mutableStateOf(ParkedTab.DASHBOARD.name) }
    var focusSpotId by rememberSaveable { mutableStateOf(-1L) }
    var mapCamera by rememberSaveable(stateSaver = MapCameraSaver) {
        mutableStateOf<MapCamera?>(null)
    }
    val tab = runCatching { ParkedTab.valueOf(tabName) }.getOrDefault(ParkedTab.DASHBOARD)

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissions = PermissionSnapshot.read(context)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissions = PermissionSnapshot.read(context)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = PermissionSnapshot.read(context)
                viewModel.refreshBondedDevices()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        settings.monitoringEnabled,
        permissions.bluetoothPermission,
        permissions.locationOk,
    ) {
        if (settings.monitoringEnabled) viewModel.ensureMonitoringRunning()
    }

    val requestPermissions: () -> Unit = {
        foregroundLauncher.launch(PermissionUtils.foregroundPermissions())
    }
    val requestBackgroundLocation: () -> Unit = {
        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val notifyFailure: (String) -> Unit = { viewModel.showMessage(it) }

    val openBatterySettings: () -> Unit = {
        if (!AppIntents.requestIgnoreBatteryOptimizations(context)) {
            notifyFailure("Nie udało się otworzyć ustawień baterii")
        }
    }
    val openAutostartSettings: () -> Unit = {
        if (!AppIntents.openAutostartSettings(context)) {
            notifyFailure("Nie udało się otworzyć ustawień autostartu")
        }
    }
    val openLocationSettings: () -> Unit = {
        if (!AppIntents.openLocationSettings(context)) {
            notifyFailure("Nie udało się otworzyć ustawień lokalizacji")
        }
    }
    val openBluetoothSettings: () -> Unit = {
        if (!AppIntents.openBluetoothSettings(context)) {
            notifyFailure("Nie udało się otworzyć ustawień Bluetooth")
        }
    }
    val openNotificationSettings: () -> Unit = {
        if (!AppIntents.openNotificationSettings(context)) {
            notifyFailure("Nie udało się otworzyć ustawień powiadomień")
        }
    }
    val openAppSettings: () -> Unit = {
        if (!AppIntents.openAppDetails(context)) {
            notifyFailure("Nie udało się otworzyć ustawień aplikacji")
        }
    }
    val navigateTo: (ParkingSpot) -> Unit = { spot ->
        if (!AppIntents.navigateTo(context, spot.latitude, spot.longitude, spot.note)) {
            notifyFailure("Brak aplikacji do nawigacji")
        }
    }
    val openMap: (ParkingSpot) -> Unit = { spot ->
        focusSpotId = spot.id
        tabName = ParkedTab.MAP.name
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ParkedTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            focusSpotId = -1L
                            tabName = item.name
                        },
                        icon = {
                            Icon(item.icon, contentDescription = item.label)
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                ParkedTab.DASHBOARD -> DashboardScreen(
                    settings = settings,
                    monitor = monitor,
                    latestSpot = latestSpot,
                    permissions = permissions,
                    saving = uiState.saving,
                    onToggleMonitoring = viewModel::setMonitoringEnabled,
                    onSaveNow = viewModel::saveCurrentLocation,
                    onCancelPending = viewModel::cancelPendingSave,
                    onOpenHistory = { tabName = ParkedTab.HISTORY.name },
                    onOpenMap = openMap,
                    onNavigate = navigateTo,
                    onOpenSettings = { tabName = ParkedTab.SETTINGS.name },
                    onRequestPermissions = requestPermissions,
                    onRequestBackgroundLocation = requestBackgroundLocation,
                    onBatterySettings = openBatterySettings,
                    onAutostartSettings = openAutostartSettings,
                    onOpenLocationSettings = openLocationSettings,
                    onOpenBluetoothSettings = openBluetoothSettings,
                )

                ParkedTab.HISTORY -> HistoryScreen(
                    spots = spots,
                    onUpdateNote = viewModel::updateNote,
                    onAttachPhoto = viewModel::attachPhoto,
                    onRemovePhoto = viewModel::removePhoto,
                    onDeleteSpot = viewModel::deleteSpot,
                    onDeleteAll = viewModel::deleteAllSpots,
                    onAddManual = viewModel::addManualSpot,
                    onExport = viewModel::exportSpots,
                    onSuggestedExportName = viewModel::exportSuggestedName,
                    onNavigate = navigateTo,
                    onOpenMap = openMap,
                )

                ParkedTab.MAP -> MapScreen(
                    spots = spots,
                    permissions = permissions,
                    focusSpotId = focusSpotId.takeIf { it > 0L },
                    initialCamera = mapCamera,
                    onCameraChanged = { camera -> mapCamera = camera },
                    onNavigate = navigateTo,
                    onRequestPermissions = requestPermissions,
                    onOpenSettings = { tabName = ParkedTab.SETTINGS.name },
                )

                ParkedTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    permissions = permissions,
                    bondedDevices = bondedDevices,
                    onRefreshDevices = viewModel::refreshBondedDevices,
                    onSelectDevice = viewModel::selectDevice,
                    onSaveDelayChange = viewModel::updateSaveDelay,
                    onGpsTimeoutChange = viewModel::updateGpsTimeout,
                    onMaxAccuracyChange = viewModel::updateMaxAccuracy,
                    onFallbackEnabledChange = viewModel::updateFallbackEnabled,
                    onFallbackMaxAgeChange = viewModel::updateFallbackMaxAge,
                    onOpenNotificationSettings = openNotificationSettings,
                    onRequestPermissions = requestPermissions,
                    onRequestBackgroundLocation = requestBackgroundLocation,
                    onBatterySettings = openBatterySettings,
                    onAutostartSettings = openAutostartSettings,
                    onOpenLocationSettings = openLocationSettings,
                    onOpenBluetoothSettings = openBluetoothSettings,
                    onOpenAppSettings = openAppSettings,
                )

                ParkedTab.DIAGNOSTICS -> DiagnosticsScreen(
                    events = diagnostics,
                    permissions = permissions,
                    monitor = monitor,
                    uiState = uiState,
                    onTestLocation = viewModel::testLocation,
                    onShareLogs = viewModel::shareDiagnostics,
                    onClearLogs = viewModel::clearDiagnostics,
                )
            }
        }
    }
}
