package io.github.bartixxx32.parked.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.export.ExportFormat
import io.github.bartixxx32.parked.ui.Formatters
import io.github.bartixxx32.parked.ui.components.EmptyState
import io.github.bartixxx32.parked.ui.components.InfoRow
import io.github.bartixxx32.parked.ui.components.SpotPhoto

@Composable
fun HistoryScreen(
    spots: List<ParkingSpot>,
    onUpdateNote: (Long, String) -> Unit,
    onAttachPhoto: (Long, Uri) -> Unit,
    onRemovePhoto: (Long) -> Unit,
    onDeleteSpot: (Long) -> Unit,
    onDeleteAll: () -> Unit,
    onAddManual: (String, String, String) -> Unit,
    onExport: (ExportFormat, Uri) -> Unit,
    onSuggestedExportName: (ExportFormat) -> String,
    onNavigate: (ParkingSpot) -> Unit,
    onOpenMap: (ParkingSpot) -> Unit,
) {
    var pendingPhotoSpotId by remember { mutableStateOf<Long?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val id = pendingPhotoSpotId
        pendingPhotoSpotId = null
        if (id != null && uri != null) onAttachPhoto(id, uri)
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
                            text = "Historia",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${spots.size} miejsc • dane pozostają na tym urządzeniu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { exportMenuOpen = true },
                            enabled = spots.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Eksportuj")
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Eksport JSON") },
                                onClick = {
                                    exportMenuOpen = false
                                    jsonExport.launch(onSuggestedExportName(ExportFormat.JSON))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Eksport CSV") },
                                onClick = {
                                    exportMenuOpen = false
                                    csvExport.launch(onSuggestedExportName(ExportFormat.CSV))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Eksport GPX") },
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
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Usuń całą historię")
                    }
                }
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Dodaj miejsce ręcznie")
                }
            }
        }

        if (spots.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "Brak zapisanych miejsc",
                    subtitle = "Połącz się z radiem i odjedź, albo dodaj miejsce ręcznie.",
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
                    onOpenMap = { onOpenMap(spot) },
                    onDelete = { deleteTarget = spot },
                    onPickPhoto = {
                        pendingPhotoSpotId = spot.id
                        photoPicker.launch("image/*")
                    },
                    onRemovePhoto = { onRemovePhoto(spot.id) },
                    onUpdateNote = { note -> onUpdateNote(spot.id, note) },
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
            title = { Text("Usunąć wpis?") },
            text = {
                Text(
                    "Usunięte zostanie miejsce z ${Formatters.dateTime(spot.savedAtEpochMs)} " +
                        "wraz z notatką i zdjęciem.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSpot(spot.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Usuń")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Anuluj")
                }
            },
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Usunąć całą historię?") },
            text = { Text("Tej operacji nie można cofnąć. Zdjęcia również zostaną usunięte.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        showDeleteAllDialog = false
                    },
                ) {
                    Text("Usuń wszystko")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Anuluj")
                }
            },
        )
    }
}

@Composable
private fun SpotCard(
    spot: ParkingSpot,
    onNavigate: () -> Unit,
    onOpenMap: () -> Unit,
    onDelete: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onUpdateNote: (String) -> Unit,
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
                        contentDescription = "Usuń wpis",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (spot.isFallback) {
                Text(
                    text = "Punkt awaryjny — ostatnia znana lokalizacja.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (spot.isMock) {
                Text(
                    text = "Lokalizacja testowa (mock).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            InfoRow("Dokładność", Formatters.accuracy(spot))
            InfoRow("Źródło pomiaru", Formatters.provider(spot.provider))
            InfoRow("Pomiar", Formatters.dateTime(spot.fixTimeEpochMs))
            InfoRow("Współrzędne", Formatters.coordinates(spot.latitude, spot.longitude))
            spot.deviceName?.let { InfoRow("Radio", it) }

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
                    label = { Text("Notatka") },
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
                        Text("Anuluj")
                    }
                    Button(
                        onClick = {
                            onUpdateNote(draft)
                            editing = false
                        },
                    ) {
                        Text("Zapisz notatkę")
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
                        text = "Brak notatki",
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
                    Text("Nawiguj")
                }
                TextButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Mapa")
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
                    Text("Notatka")
                }
                TextButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (spot.photoPath == null) "Zdjęcie" else "Zmień zdjęcie")
                }
            }
            if (spot.photoPath != null) {
                TextButton(onClick = onRemovePhoto, modifier = Modifier.fillMaxWidth()) {
                    Text("Usuń zdjęcie")
                }
            }
        }
    }
}

@Composable
private fun ManualAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj miejsce ręcznie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Podaj współrzędne dziesiętne, np. 52.2297 oraz 21.0122.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it },
                    label = { Text("Szerokość geograficzna") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it },
                    label = { Text("Długość geograficzna") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notatka (opcjonalnie)") },
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
                Text("Dodaj")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Anuluj")
            }
        },
    )
}
