package io.github.bartixxx32.parked.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.ui.Formatters
import io.github.bartixxx32.parked.ui.PermissionSnapshot
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(
    spots: List<ParkingSpot>,
    permissions: PermissionSnapshot,
    focusSpotId: Long?,
    onNavigate: (ParkingSpot) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "Parked/${io.github.bartixxx32.parked.BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(52.0, 19.0))
        }
    }
    val markerOverlays = remember { mutableListOf<Marker>() }
    var locationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    val latest = remember(spots) { spots.maxByOrNull { it.savedAtEpochMs } }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            locationOverlay?.let { overlay -> runCatching { overlay.disableMyLocation() } }
            markerOverlays.clear()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(spots, focusSpotId) {
        markerOverlays.forEach { marker -> mapView.overlays.remove(marker) }
        markerOverlays.clear()
        spots.forEach { spot ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(spot.latitude, spot.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = Formatters.dateTime(spot.savedAtEpochMs)
                snippet = buildString {
                    append(Formatters.source(spot))
                    append(" • dokładność: ").append(Formatters.accuracy(spot))
                    if (spot.note.isNotBlank()) append("\n").append(spot.note)
                }
                relatedObject = spot
            }
            markerOverlays += marker
            mapView.overlays.add(marker)
        }
        val focus = spots.firstOrNull { it.id == focusSpotId } ?: latest
        if (focus != null) {
            mapView.controller.setZoom(16.0)
            mapView.controller.animateTo(GeoPoint(focus.latitude, focus.longitude))
        }
        mapView.invalidate()
    }

    LaunchedEffect(permissions.locationOk, mapView) {
        if (permissions.locationOk) {
            if (locationOverlay == null) {
                val overlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
                mapView.overlays.add(0, overlay)
                runCatching { overlay.enableMyLocation() }
                locationOverlay = overlay
            }
        } else {
            locationOverlay?.let { overlay ->
                runCatching { overlay.disableMyLocation() }
                mapView.overlays.remove(overlay)
                locationOverlay = null
            }
        }
        mapView.invalidate()
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
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Mapa",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val spot = latest
                    if (spot == null) {
                        Text(
                            text = "Brak zapisanych miejsc. Piny pojawią się tutaj po zapisie.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = Formatters.dateTime(spot.savedAtEpochMs),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = Formatters.coordinates(spot.latitude, spot.longitude) +
                                " • " + Formatters.accuracy(spot),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(
                                onClick = { onNavigate(spot) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.Filled.Navigation,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text("Nawiguj do ostatniego")
                            }
                            TextButton(
                                onClick = {
                                    mapView.controller.setZoom(17.0)
                                    mapView.controller.animateTo(
                                        GeoPoint(spot.latitude, spot.longitude),
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Filled.MyLocation,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                Text("Wyśrodkuj na miejscu")
                            }
                        }
                    }
                }
            }

            if (!permissions.locationOk) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Brak uprawnienia do lokalizacji — nie widać bieżącej pozycji.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = onOpenSettings) {
                            Text("Ustawienia")
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
