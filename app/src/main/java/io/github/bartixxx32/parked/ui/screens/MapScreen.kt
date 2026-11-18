package io.github.bartixxx32.parked.ui.screens

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.data.db.SpotSource
import io.github.bartixxx32.parked.ui.Formatters
import io.github.bartixxx32.parked.ui.PermissionSnapshot
import io.github.bartixxx32.parked.ui.components.TargetCompass
import io.github.bartixxx32.parked.ui.components.cardinalDirection
import io.github.bartixxx32.parked.ui.components.rememberDeviceAzimuth
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

data class MapCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
)

val MapCameraSaver = listSaver<MapCamera?, Any>(
    save = { camera ->
        if (camera == null) {
            emptyList()
        } else {
            listOf(camera.latitude, camera.longitude, camera.zoom)
        }
    },
    restore = { values ->
        if (values.size == 3) {
            MapCamera(
                latitude = values[0] as Double,
                longitude = values[1] as Double,
                zoom = values[2] as Float,
            )
        } else {
            null
        }
    },
)

@Composable
fun MapScreen(
    spots: List<ParkingSpot>,
    permissions: PermissionSnapshot,
    focusSpotId: Long?,
    initialCamera: MapCamera?,
    onCameraChanged: (MapCamera) -> Unit,
    onNavigate: (ParkingSpot) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var selectedSpotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var didInitialCenter by remember { mutableStateOf(false) }

    val latest = remember(spots) { spots.maxByOrNull { it.savedAtEpochMs } }
    val selectedSpot = remember(spots, selectedSpotId) {
        spots.firstOrNull { it.id == selectedSpotId }
    }
    val target = selectedSpot ?: latest

    val spotsState = rememberUpdatedState(spots)
    val selectedIdState = rememberUpdatedState(selectedSpotId)
    val cameraCallback by rememberUpdatedState(onCameraChanged)

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "Parked/${io.github.bartixxx32.parked.BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            if (initialCamera != null) {
                controller.setZoom(initialCamera.zoom.toDouble())
                controller.setCenter(GeoPoint(initialCamera.latitude, initialCamera.longitude))
            } else {
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(GeoPoint(52.0, 19.0))
            }
        }
    }

    val pinOverlay = remember(mapView) {
        SpotPinOverlay(
            mapView = mapView,
            spotsProvider = { spotsState.value },
            selectedIdProvider = { selectedIdState.value },
            onSelect = { spot -> selectedSpotId = spot?.id },
        )
    }

    val cameraListener = remember(mapView) {
        object : MapListener {
            private var lastPublished = 0L

            private fun publishCamera(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastPublished < CAMERA_UPDATE_INTERVAL_MS) return
                lastPublished = now
                val center = mapView.mapCenter ?: return
                cameraCallback(
                    MapCamera(
                        latitude = center.latitude,
                        longitude = center.longitude,
                        zoom = mapView.zoomLevelDouble.toFloat(),
                    ),
                )
            }

            override fun onScroll(event: ScrollEvent?): Boolean {
                publishCamera()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                publishCamera()
                return false
            }
        }
    }

    DisposableEffect(mapView, pinOverlay) {
        mapView.overlays.add(pinOverlay)
        mapView.addMapListener(cameraListener)
        mapView.onResume()
        mapView.invalidate()
        onDispose {
            val center = mapView.mapCenter
            if (center != null) {
                cameraCallback(
                    MapCamera(
                        latitude = center.latitude,
                        longitude = center.longitude,
                        zoom = mapView.zoomLevelDouble.toFloat(),
                    ),
                )
            }
            mapView.removeMapListener(cameraListener)
            mapView.overlays.remove(pinOverlay)
            locationOverlay?.let { overlay -> runCatching { overlay.disableMyLocation() } }
            locationOverlay = null
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(spots, selectedSpotId) {
        mapView.invalidate()
    }

    LaunchedEffect(permissions.locationOk, mapView) {
        if (!permissions.locationOk) {
            locationOverlay?.let { overlay ->
                runCatching { overlay.disableMyLocation() }
                mapView.overlays.remove(overlay)
            }
            locationOverlay = null
            userLocation = null
            return@LaunchedEffect
        }

        val overlay = locationOverlay ?: MyLocationNewOverlay(
            GpsMyLocationProvider(context),
            mapView,
        ).also { created ->
            runCatching { created.enableMyLocation() }
            mapView.overlays.add(0, created)
            locationOverlay = created
        }
        mapView.invalidate()

        while (isActive) {
            userLocation = overlay.myLocation
            delay(LOCATION_POLL_MS)
        }
    }

    LaunchedEffect(spots, focusSpotId) {
        val focused = spots.firstOrNull { it.id == focusSpotId }
        if (focused != null) {
            selectedSpotId = focused.id
            didInitialCenter = true
            mapView.controller.setZoom(FOCUS_ZOOM)
            mapView.controller.setCenter(GeoPoint(focused.latitude, focused.longitude))
            return@LaunchedEffect
        }
        if (didInitialCenter) return@LaunchedEffect
        if (initialCamera != null) {
            didInitialCenter = true
            return@LaunchedEffect
        }
        val initialTarget = latest
        if (initialTarget != null) {
            mapView.controller.setZoom(FOCUS_ZOOM)
            mapView.controller.setCenter(GeoPoint(initialTarget.latitude, initialTarget.longitude))
            didInitialCenter = true
        }
    }

    val distanceBearing = remember(target?.id, userLocation?.latitude, userLocation?.longitude) {        val spot = target
        val fix = userLocation
        if (spot == null || fix == null) {
            null
        } else {
            val results = FloatArray(3)
            Location.distanceBetween(
                fix.latitude,
                fix.longitude,
                spot.latitude,
                spot.longitude,
                results,
            )
            DistanceBearing(meters = results[0], bearing = results[1])
        }
    }

    val centerOnMyLocation: () -> Unit = {
        if (!permissions.locationOk) {
            onRequestPermissions()
        } else {
            val fix = userLocation ?: locationOverlay?.myLocation
            if (fix != null) {
                mapView.controller.setZoom(FOCUS_ZOOM)
                mapView.controller.animateTo(fix)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Mapa",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (spots.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${spots.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!permissions.locationOk) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.97f),
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Brak dostępu do lokalizacji",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onRequestPermissions) {
                            Text("Przyznaj")
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapControlButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "Przybliż mapę",
                    onClick = { mapView.controller.zoomIn() },
                )
                MapControlButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = "Oddal mapę",
                    onClick = { mapView.controller.zoomOut() },
                )
                MapControlButton(
                    icon = Icons.Filled.MyLocation,
                    contentDescription = "Moja pozycja",
                    highlighted = permissions.locationOk && userLocation != null,
                    onClick = centerOnMyLocation,
                )
            }

            SpotCard(
                spot = target,
                isSelected = selectedSpot != null,
                distanceBearing = distanceBearing,
                hasLocationAccess = permissions.locationOk,
                onNavigate = { spot -> onNavigate(spot) },
                onClearSelection = { selectedSpotId = null },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun MapControlButton(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
        },
        shadowElevation = 5.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun SpotCard(
    spot: ParkingSpot?,
    isSelected: Boolean,
    distanceBearing: DistanceBearing?,
    hasLocationAccess: Boolean,
    onNavigate: (ParkingSpot) -> Unit,
    onClearSelection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val azimuthDegrees = rememberDeviceAzimuth()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(38.dp)
                    .height(4.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }

            if (spot == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Brak zapisanych miejsc",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Po rozłączeniu z radiem zapiszę tutaj miejsce postoju.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!hasLocationAccess) {
                    TextButton(onClick = onOpenSettings) {
                        Text("Sprawdź uprawnienia w ustawieniach")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (isSelected) "WYBRANE MIEJSCE" else "OSTATNIE MIEJSCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = Formatters.dateTime(spot.savedAtEpochMs),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = Formatters.coordinates(spot.latitude, spot.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoChip(
                                text = spot.accuracyMeters?.let { "±${it.roundToInt()} m" }
                                    ?: "bez dokładności",
                                emphasized = false,
                            )
                            InfoChip(
                                text = Formatters.source(spot),
                                emphasized = false,
                            )
                            if (spot.isFallback) {
                                InfoChip(text = "awaryjny", emphasized = true)
                            }
                        }
                    }

                    if (distanceBearing != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TargetCompass(
                                azimuthDegrees = azimuthDegrees,
                                bearingDegrees = distanceBearing.bearing,
                                modifier = Modifier.size(92.dp),
                            )
                            Text(
                                text = "${distanceBearing.meters.roundToInt()} m",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${cardinalDirection(distanceBearing.bearing)} • " +
                                    "${distanceBearing.bearing.roundToInt()}°",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (distanceBearing == null) {
                    Text(
                        text = if (hasLocationAccess) {
                            "Ustalam Twoją pozycję, aby pokazać kompas do celu…"
                        } else {
                            "Włącz lokalizację, aby zobaczyć kompas i odległość do celu."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (azimuthDegrees == null) {
                    Text(
                        text = "Brak czujnika kompasu — strzałka wskazuje kierunek względem północy.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (spot.note.isNotBlank()) {
                    Text(
                        text = spot.note,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onNavigate(spot) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Nawiguj")
                    }
                    if (isSelected) {
                        IconButton(
                            onClick = onClearSelection,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Odznacz miejsce",
                            )
                        }
                    }
                }
            }

            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun InfoChip(text: String, emphasized: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (emphasized) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Draws every saved spot as an app-styled pin on top of the map. The default osmdroid
 * marker balloon is intentionally not used: pins are drawn on a canvas so they keep a
 * constant size, follow the theme accent and can be selected with a larger halo.
 */
private class SpotPinOverlay(
    private val mapView: MapView,
    private val spotsProvider: () -> List<ParkingSpot>,
    private val selectedIdProvider: () -> Long?,
    private val onSelect: (ParkingSpot?) -> Unit,
) : Overlay() {

    private val density = mapView.resources.displayMetrics.density
    private val point = Point()
    private val path = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x332563EB
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val selectedId = selectedIdProvider()
        spotsProvider().forEach { spot ->
            mapView.projection.toPixels(GeoPoint(spot.latitude, spot.longitude), point)
            drawPin(
                canvas = canvas,
                x = point.x.toFloat(),
                y = point.y.toFloat(),
                selected = spot.id == selectedId,
                fallback = spot.source == SpotSource.FALLBACK || spot.isFallback,
            )
        }
    }

    private fun drawPin(
        canvas: Canvas,
        x: Float,
        y: Float,
        selected: Boolean,
        fallback: Boolean,
    ) {
        val scale = if (selected) 1.25f else 1f
        val radius = PIN_RADIUS_DP * density * scale
        val height = PIN_HEIGHT_DP * density * scale
        val centerY = y - height + radius

        shadowPaint.color = 0x33000000
        canvas.drawOval(
            x - radius * 0.85f,
            y - 3f * density,
            x + radius * 0.85f,
            y + 2.5f * density,
            shadowPaint,
        )

        if (selected) {
            canvas.drawCircle(x, centerY, radius * 1.9f, haloPaint)
        }

        path.reset()
        path.addCircle(x, centerY, radius, Path.Direction.CW)
        val wing = radius * 0.64f
        path.moveTo(x - wing, centerY + radius * 0.55f)
        path.lineTo(x, y)
        path.lineTo(x + wing, centerY + radius * 0.55f)
        path.close()

        fillPaint.color = if (fallback) FALLBACK_PIN_COLOR else PIN_COLOR
        canvas.drawPath(path, fillPaint)

        ringPaint.strokeWidth = 2.2f * density
        canvas.drawCircle(x, centerY, radius - ringPaint.strokeWidth / 2f, ringPaint)
        canvas.drawCircle(x, centerY, radius * 0.34f, dotPaint)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val selectedId = selectedIdProvider()
        var best: ParkingSpot? = null
        var bestDistance = PIN_TOUCH_RADIUS_DP * density

        spotsProvider().forEach { spot ->
            mapView.projection.toPixels(GeoPoint(spot.latitude, spot.longitude), point)
            val dx = point.x.toFloat() - e.x
            val dy = (point.y.toFloat() - PIN_HEIGHT_DP * density * 0.55f) - e.y
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (distance < bestDistance) {
                bestDistance = distance
                best = spot
            }
        }

        val hit = best
        if (hit != null) {
            onSelect(hit)
            mapView.invalidate()
            return true
        }
        if (selectedId != null) {
            onSelect(null)
            mapView.invalidate()
            return true
        }
        return false
    }

    private companion object {
        const val PIN_RADIUS_DP = 9f
        const val PIN_HEIGHT_DP = 27f
        const val PIN_TOUCH_RADIUS_DP = 30f
        const val PIN_COLOR = 0xFF2563EB.toInt()
        const val FALLBACK_PIN_COLOR = 0xFFD97706.toInt()
    }
}

private data class DistanceBearing(
    val meters: Float,
    val bearing: Float,
)

private const val DEFAULT_ZOOM = 6.0
private const val FOCUS_ZOOM = 17.0
private const val LOCATION_POLL_MS = 1_000L
private const val CAMERA_UPDATE_INTERVAL_MS = 350L
