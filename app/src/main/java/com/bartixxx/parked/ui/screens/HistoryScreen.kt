package com.bartixxx.parked.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.FileProvider
import com.bartixxx.parked.R
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.stats.calculateParkingStats
import com.bartixxx.parked.export.ExportFormat
import com.bartixxx.parked.ui.Formatters
import com.bartixxx.parked.ui.components.EmptyState
import com.bartixxx.parked.ui.components.InfoRow
import com.bartixxx.parked.ui.components.SpotPhoto
import com.bartixxx.parked.ui.components.StatisticsSection
import java.io.File
import java.util.Locale

@Composable
fun HistoryScreen(
    spots: List<ParkingSpot>,
    onUpdateNote: (Long, String) -> Unit,
    onAttachPhoto: (Long, Uri) -> Unit,
    onPhotoError: () -> Unit,
    onRemovePhoto: (Long) -> Unit,
    onDeleteSpot: (Long) -> Unit,
    onDeleteAll: () -> Unit,
    onAddManual: (String, String, String) -> Unit,
    onExport: (ExportFormat, Uri) -> Unit,
    onSuggestedExportName: (ExportFormat) -> String,
    onNavigate: (ParkingSpot) -> Unit,
    onWalk: (ParkingSpot) -> Unit,
    onOpenMap: (ParkingSpot) -> Unit,
    canSendToFamily: Boolean,
    familyPhotoSending: Boolean,
    familySentSpotIds: Set<Long>,
    onSendToFamily: (ParkingSpot) -> Unit,
) {
    val context = LocalContext.current
    var pendingPhotoSpotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var photoTargetSpotId by rememberSaveable { mutableStateOf<Long?>(null) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val id = pendingPhotoSpotId
        pendingPhotoSpotId = null
        if (id != null && uri != null) onAttachPhoto(id, uri)
    }
    val cameraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val id = pendingPhotoSpotId
        val uri = pendingCameraUri?.let(Uri::parse)
        val path = pendingCameraPath
        pendingPhotoSpotId = null
        pendingCameraUri = null
        pendingCameraPath = null
        if (success && id != null && uri != null) {
            onAttachPhoto(id, uri)
        } else if (path != null) {
            File(path).delete()
        }
    }

    val jsonExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType),
    ) { uri -> uri?.let { onExport(ExportFormat.JSON, it) } }
    val csvExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType),
    ) { uri -> uri?.let { onExport(ExportFormat.CSV, it) } }
    val gpxExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.GPX.mimeType),
    ) { uri -> uri?.let { onExport(ExportFormat.GPX, it) } }

    var exportMenuOpen by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ParkingSpot?>(null) }
    var sendTarget by remember { mutableStateOf<ParkingSpot?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tab_history),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.history_count,
                                spots.size,
                                spots.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { exportMenuOpen = true },
                            enabled = spots.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.action_export))
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                onClick = {
                                    exportMenuOpen = false
                                    jsonExport.launch(onSuggestedExportName(ExportFormat.JSON))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_csv)) },
                                onClick = {
                                    exportMenuOpen = false
                                    csvExport.launch(onSuggestedExportName(ExportFormat.CSV))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_gpx)) },
                                onClick = {
                                    exportMenuOpen = false
                                    gpxExport.launch(onSuggestedExportName(ExportFormat.GPX))
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { showDeleteAllDialog = true },
                        enabled = spots.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.delete_history))
                    }
                }
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_manual_spot))
                }
            }
        }

        if (spots.isNotEmpty()) {
            item(key = "statistics") {
                StatisticsSection(stats = remember(spots) { calculateParkingStats(spots) })
            }
        }

        if (spots.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.no_spots),
                    subtitle = stringResource(R.string.history_empty_subtitle),
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(spots, key = { it.id }) { spot ->
                SpotCard(
                    spot = spot,
                    onNavigate = { onNavigate(spot) },
                    onWalk = { onWalk(spot) },
                    onOpenMap = { onOpenMap(spot) },
                    onDelete = { deleteTarget = spot },
                    onPickPhoto = { photoTargetSpotId = spot.id },
                    onRemovePhoto = { onRemovePhoto(spot.id) },
                    onUpdateNote = { note -> onUpdateNote(spot.id, note) },
                    canSendToFamily = canSendToFamily,
                    familyPhotoSending = familyPhotoSending,
                    photoSent = spot.id in familySentSpotIds,
                    onSendPhoto = { sendTarget = spot },
                )
            }
        }

        item(key = "bottom") {
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        ManualAddDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { latitude, longitude, note ->
                onAddManual(latitude, longitude, note)
            },
        )
    }

    deleteTarget?.let { spot ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_spot_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_spot_message,
                        Formatters.dateTime(spot.savedAtEpochMs),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSpot(spot.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    sendTarget?.let { spot ->
        AlertDialog(
            onDismissRequest = { sendTarget = null },
            title = { Text(stringResource(R.string.family_send_photo_title)) },
            text = { Text(stringResource(R.string.family_send_photo_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sendTarget = null
                        onSendToFamily(spot)
                    },
                    enabled = !familyPhotoSending,
                ) {
                    Text(stringResource(R.string.family_send_photo_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { sendTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    photoTargetSpotId?.let { spotId ->
        AlertDialog(
            onDismissRequest = { photoTargetSpotId = null },
            title = { Text(stringResource(R.string.photo_source_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.photo_source_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = {
                            photoTargetSpotId = null
                            val target = createCameraTarget(context)
                            if (target != null) {
                                pendingPhotoSpotId = spotId
                                pendingCameraUri = target.first.toString()
                                pendingCameraPath = target.second
                                runCatching { cameraPicker.launch(target.first) }
                                    .onFailure {
                                        pendingPhotoSpotId = null
                                        pendingCameraUri = null
                                        pendingCameraPath = null
                                        File(target.second).delete()
                                        onPhotoError()
                                    }
                            } else {
                                onPhotoError()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_take_photo))
                    }
                    OutlinedButton(
                        onClick = {
                            photoTargetSpotId = null
                            pendingPhotoSpotId = spotId
                            galleryPicker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.action_choose_gallery))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { photoTargetSpotId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.delete_all_title)) },
            text = { Text(stringResource(R.string.delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        showDeleteAllDialog = false
                    },
                ) {
                    Text(stringResource(R.string.delete_all_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SpotCard(
    spot: ParkingSpot,
    onNavigate: () -> Unit,
    onWalk: () -> Unit,
    onOpenMap: () -> Unit,
    onDelete: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onUpdateNote: (String) -> Unit,
    canSendToFamily: Boolean,
    familyPhotoSending: Boolean,
    photoSent: Boolean,
    onSendPhoto: () -> Unit,
) {
    var editing by remember(spot.id) { mutableStateOf(false) }
    var draft by remember(spot.id, spot.note) { mutableStateOf(spot.note) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Formatters.dateTime(spot.savedAtEpochMs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = Formatters.source(spot) + " • " + Formatters.age(spot.savedAtEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_entry),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (spot.isFallback) {
                Text(
                    text = stringResource(R.string.fallback_point_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (spot.isMock) {
                Text(
                    text = stringResource(R.string.mock_point_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            InfoRow(stringResource(R.string.label_accuracy), Formatters.accuracy(spot))
            InfoRow(stringResource(R.string.label_source), Formatters.provider(spot.provider))
            InfoRow(stringResource(R.string.label_fix), Formatters.dateTime(spot.fixTimeEpochMs))
            InfoRow(
                stringResource(R.string.label_coordinates),
                Formatters.coordinates(spot.latitude, spot.longitude),
            )
            spot.deviceName?.let { InfoRow(stringResource(R.string.label_radio), it) }

            if (spot.photoPath != null) {
                SpotPhoto(
                    path = spot.photoPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.label_note)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            draft = spot.note
                            editing = false
                        },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            onUpdateNote(draft)
                            editing = false
                        },
                    ) {
                        Text(stringResource(R.string.save_note))
                    }
                }
            } else {
                if (spot.note.isNotBlank()) {
                    Text(
                        text = spot.note,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.no_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNavigate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_navigate))
                }
                TextButton(onClick = onWalk, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_walk))
                }
                TextButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_map))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        draft = spot.note
                        editing = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.action_note))
                }
                TextButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(
                        if (spot.photoPath == null) {
                            stringResource(R.string.action_photo)
                        } else {
                            stringResource(R.string.action_change_photo)
                        },
                    )
                }
            }
            if (spot.photoPath != null) {
                if (canSendToFamily) {
                    FilledTonalButton(
                        onClick = onSendPhoto,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !familyPhotoSending && !photoSent,
                    ) {
                        Icon(
                            if (photoSent) Icons.Filled.Check else Icons.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            stringResource(
                                when {
                                    photoSent -> R.string.family_photo_sent
                                    familyPhotoSending -> R.string.family_photo_sending
                                    else -> R.string.family_send_photo
                                },
                            ),
                        )
                    }
                }
                TextButton(onClick = onRemovePhoto, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_remove_photo))
                }
            }
        }
    }
}

@Composable
fun ManualAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_manual_spot)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.manual_coords_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Map,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.map_picker_pick))
                }
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text(stringResource(R.string.label_latitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text(stringResource(R.string.label_longitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.label_note_optional)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(latitude, longitude, note)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    if (pickerOpen) {
        MapPickerDialog(
            initialLatitude = latitude.trim().toDoubleOrNull(),
            initialLongitude = longitude.trim().toDoubleOrNull(),
            onDismiss = { pickerOpen = false },
            onConfirm = { pickedLatitude, pickedLongitude ->
                latitude = String.format(Locale.US, "%.6f", pickedLatitude)
                longitude = String.format(Locale.US, "%.6f", pickedLongitude)
                pickerOpen = false
            },
        )
    }
}

/**
 * Creates a private cache file for the system camera app. The app itself does
 * not request the CAMERA permission; the capture happens in the camera app and
 * the result is copied into the spot photo directory.
 */
private fun createCameraTarget(context: Context): Pair<Uri, String>? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - CAMERA_CACHE_MAX_AGE_MS
    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.lastModified() < cutoff) file.delete()
    }
    val file = File.createTempFile("spot_", ".jpg", dir)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    uri to file.absolutePath
}.getOrNull()

private const val CAMERA_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
