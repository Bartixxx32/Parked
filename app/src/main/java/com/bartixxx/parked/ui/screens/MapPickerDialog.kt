package com.bartixxx.parked.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.bartixxx.parked.BuildConfig
import com.bartixxx.parked.R
import com.bartixxx.parked.location.LocationValidator
import com.bartixxx.parked.ui.Formatters
import java.io.File
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Full-screen map used to pick coordinates for a manual spot. The crosshair at
 * the centre of the screen marks the chosen point, so the user only has to pan
 * and zoom instead of typing latitude and longitude.
 */
@Composable
fun MapPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val start = remember(initialLatitude, initialLongitude) {
        initialPoint(context, initialLatitude, initialLongitude)
    }
    var center by remember { mutableStateOf<IGeoPoint>(start.point) }
    var myLocation by remember { mutableStateOf(lastKnownLocation(context)) }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = "Parked/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            controller.setZoom(start.zoom)
            controller.setCenter(start.point)
        }
    }

    val mapListener = remember(mapView) {
        object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                mapView.mapCenter?.let { center = it }
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                mapView.mapCenter?.let { center = it }
                return false
            }
        }
    }

    DisposableEffect(mapView) {
        mapView.addMapListener(mapListener)
        mapView.onResume()
        onDispose {
            mapView.removeMapListener(mapListener)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = stringResource(R.string.map_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                PickerControlButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_zoom_in),
                    onClick = { mapView.controller.zoomIn() },
                )
                PickerControlButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.cd_zoom_out),
                    onClick = { mapView.controller.zoomOut() },
                )
                PickerControlButton(
                    icon = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.cd_my_location),
                    enabled = myLocation != null,
                    onClick = {
                        val fix = lastKnownLocation(context) ?: return@PickerControlButton
                        myLocation = fix
                        mapView.controller.setZoom(PICKER_FOCUS_ZOOM)
                        mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                    },
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = Formatters.coordinates(center.latitude, center.longitude),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.map_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(
                            onClick = { onConfirm(center.latitude, center.longitude) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.map_picker_use))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 6.dp,
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.padding(10.dp).size(24.dp),
        )
    }
}

private data class PickerStart(
    val point: GeoPoint,
    val zoom: Double,
)

private fun initialPoint(
    context: Context,
    latitude: Double?,
    longitude: Double?,
): PickerStart {
    if (
        latitude != null &&
        longitude != null &&
        LocationValidator.coordinatesValid(latitude, longitude)
    ) {
        return PickerStart(GeoPoint(latitude, longitude), PICKER_FOCUS_ZOOM)
    }
    val fix = lastKnownLocation(context)
    if (fix != null) {
        return PickerStart(GeoPoint(fix.latitude, fix.longitude), PICKER_FOCUS_ZOOM)
    }
    return PickerStart(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE), PICKER_DEFAULT_ZOOM)
}

private fun lastKnownLocation(context: Context): Location? {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    return providers
        .mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        .maxByOrNull { it.time }
}

private const val DEFAULT_LATITUDE = 52.0
private const val DEFAULT_LONGITUDE = 19.0
private const val PICKER_DEFAULT_ZOOM = 6.0
private const val PICKER_FOCUS_ZOOM = 17.0
