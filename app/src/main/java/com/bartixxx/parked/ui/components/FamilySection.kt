package com.bartixxx.parked.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bartixxx.parked.R
import com.bartixxx.parked.family.DEFAULT_FAMILY_SERVER
import com.bartixxx.parked.family.FamilyCrypto
import com.bartixxx.parked.family.FamilyError
import com.bartixxx.parked.family.FamilyPalette
import com.bartixxx.parked.family.FamilyState
import com.bartixxx.parked.family.InviteCode
import com.bartixxx.parked.ui.Formatters
import com.bartixxx.parked.util.AppIntents

/**
 * Settings card for the optional family mode. Kept out of SettingsScreen so the
 * screen stays a list of sections instead of a thousand callbacks.
 */
@Composable
fun FamilySection(
    state: FamilyState,
    pairedCarName: String,
    onCreate: (String) -> Unit,
    onJoin: (String, String) -> Unit,
    onLeave: () -> Unit,
    onRefresh: () -> Unit,
    onRotate: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetShareNote: (Boolean) -> Unit,
    onSetCarLabel: (String) -> Unit,
    onSetServerUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val credentials = state.credentials
    val defaultCarLabel = rememberDefaultCarLabel(credentials.carLabel, pairedCarName)
    val unnamedCar = stringResource(R.string.family_unnamed_car)
    val joinedCarLabel = when {
        credentials.carLabel.isNotBlank() -> credentials.carLabel
        defaultCarLabel.isNotBlank() -> defaultCarLabel
        else -> unnamedCar
    }
    val isDefaultServer = credentials.serverUrl == DEFAULT_FAMILY_SERVER
    val inviteLink = credentials.inviteLink
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showName by rememberSaveable { mutableStateOf(false) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var showLeave by rememberSaveable { mutableStateOf(false) }
    var showRotate by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.family_title)) {
        if (!state.joined) {
            Text(
                text = stringResource(R.string.family_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.family_create))
                }
                FilledTonalButton(
                    onClick = { showJoin = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.family_join))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Group,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.family_joined_as, joinedCarLabel),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (state.lastSyncEpochMs == null) {
                            stringResource(R.string.family_never_synced)
                        } else {
                            stringResource(
                                R.string.family_last_sync,
                                Formatters.age(state.lastSyncEpochMs),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButtonWithLabel(
                    icon = Icons.Filled.Refresh,
                    label = stringResource(R.string.family_refresh),
                    enabled = !state.loading,
                    onClick = onRefresh,
                )
            }

            if (credentials.encrypted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.family_encrypted_badge),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.family_encrypted_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.family_plaintext_badge),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.family_plaintext_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FamilySwitchRow(
                title = stringResource(R.string.family_share_title),
                subtitle = stringResource(R.string.family_share_desc),
                checked = state.enabled,
                onCheckedChange = onSetEnabled,
            )

            if (credentials.encrypted) {
                FamilySwitchRow(
                    title = stringResource(R.string.family_share_note_title),
                    subtitle = stringResource(R.string.family_share_note_desc),
                    checked = credentials.shareNote,
                    onCheckedChange = onSetShareNote,
                )
            }

            HorizontalDivider()

            when {
                credentials.encrypted && inviteLink != null -> {
                    Text(
                        text = stringResource(R.string.family_invite_link),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = inviteLink,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, inviteLink)
                                copied = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (copied) R.string.family_link_copied
                                    else R.string.family_copy_link,
                                ),
                            )
                        }
                        TextButton(
                            onClick = {
                                AppIntents.shareText(
                                    context,
                                    context.getString(
                                        R.string.family_share_link_text,
                                        inviteLink,
                                    ),
                                    context.getString(R.string.family_share_subject),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.family_share_link))
                        }
                        if (credentials.isAdmin) {
                            TextButton(
                                onClick = { showRotate = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.family_rotate_link))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.family_encrypted_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                credentials.inviteCode != null -> {
                    val code = credentials.inviteCode.orEmpty()
                    Text(
                        text = stringResource(R.string.family_invite_code),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = InviteCode.format(code),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, code)
                                copied = true
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (copied) R.string.family_code_copied
                                    else R.string.family_copy_code,
                                ),
                            )
                        }
                        TextButton(
                            onClick = {
                                AppIntents.shareText(
                                    context,
                                    context.getString(
                                        R.string.family_share_text,
                                        InviteCode.format(code),
                                    ),
                                    context.getString(R.string.family_share_subject),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.family_share_code))
                        }
                        if (credentials.isAdmin) {
                            TextButton(
                                onClick = { showRotate = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.family_rotate_code))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.family_members),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.members.isEmpty()) {
                Text(
                    text = stringResource(R.string.family_no_members),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.members.forEach { member ->
                    FamilyMemberRow(
                        carLabel = member.carLabel.ifBlank {
                            stringResource(R.string.family_encrypted_car)
                        },
                        deviceId = member.deviceId,
                        isYou = member.deviceId == credentials.deviceId,
                        spotAge = member.spot?.savedAtEpochMs
                            ?.let { Formatters.age(it) },
                    )
                }
            }

            if (state.pendingUpload) {
                Text(
                    text = stringResource(R.string.family_pending_upload),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.sendingPhoto) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.family_photo_sending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (credentials.encrypted) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.family_photos),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!state.photoUploadEnabled) {
                    Text(
                        text = stringResource(R.string.family_photos_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.photos.isEmpty()) {
                    Text(
                        text = stringResource(R.string.family_photos_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.family_photos_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.photos.forEach { photo ->
                            Column(
                                modifier = Modifier.width(132.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                SpotPhoto(
                                    path = photo.filePath,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    maxSize = 480,
                                )
                                Text(
                                    text = photo.senderLabel.ifBlank {
                                        stringResource(R.string.family_encrypted_car)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = Formatters.age(photo.savedAtEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    enabled = !state.loading,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.family_refresh))
                }
                FilledTonalButton(
                    onClick = { showName = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.family_change_name))
                }
            }
            TextButton(
                onClick = { showLeave = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.family_leave),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.error?.let { error ->
            Text(
                text = familyErrorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.family_server_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (isDefaultServer) {
                "${stringResource(R.string.family_server_default)} · ${credentials.serverUrl}"
            } else {
                credentials.serverUrl
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.joined) {
            Text(
                text = stringResource(R.string.family_server_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { showServer = true }) {
                    Text(stringResource(R.string.action_change))
                }
                if (!isDefaultServer) {
                    TextButton(onClick = { onSetServerUrl(DEFAULT_FAMILY_SERVER) }) {
                        Text(stringResource(R.string.family_server_use_default))
                    }
                }
            }
            Text(
                text = stringResource(R.string.family_server_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showCreate) {
        FamilyNameDialog(
            title = stringResource(R.string.family_create_title),
            description = stringResource(R.string.family_car_label_help),
            initialName = defaultCarLabel,
            confirmLabel = stringResource(R.string.family_create),
            onDismiss = { showCreate = false },
            onConfirm = { label ->
                showCreate = false
                onCreate(label)
            },
        )
    }
    if (showJoin) {
        FamilyJoinDialog(
            initialName = defaultCarLabel,
            onDismiss = { showJoin = false },
            onConfirm = { code, label ->
                showJoin = false
                onJoin(code, label)
            },
        )
    }
    if (showName) {
        FamilyNameDialog(
            title = stringResource(R.string.family_name_title),
            description = stringResource(R.string.family_car_label_help),
            initialName = credentials.carLabel.ifBlank { defaultCarLabel },
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { showName = false },
            onConfirm = { label ->
                showName = false
                onSetCarLabel(label)
            },
        )
    }
    if (showServer) {
        FamilyServerDialog(
            initialUrl = credentials.serverUrl,
            onDismiss = { showServer = false },
            onConfirm = { url ->
                showServer = false
                onSetServerUrl(url)
            },
        )
    }
    if (showLeave) {
        ConfirmDialog(
            title = stringResource(R.string.family_leave_title),
            message = stringResource(R.string.family_leave_message),
            confirmLabel = stringResource(R.string.family_leave),
            destructive = true,
            onDismiss = { showLeave = false },
            onConfirm = {
                showLeave = false
                onLeave()
            },
        )
    }
    if (showRotate) {
        ConfirmDialog(
            title = stringResource(
                if (credentials.encrypted) {
                    R.string.family_rotate_link_title
                } else {
                    R.string.family_rotate_title
                },
            ),
            message = stringResource(
                if (credentials.encrypted) {
                    R.string.family_rotate_link_message
                } else {
                    R.string.family_rotate_message
                },
            ),
            confirmLabel = stringResource(
                if (credentials.encrypted) {
                    R.string.family_rotate_link
                } else {
                    R.string.family_rotate_code
                },
            ),
            destructive = false,
            onDismiss = { showRotate = false },
            onConfirm = {
                showRotate = false
                onRotate()
            },
        )
    }
}

/**
 * Prefer the label saved on this device; a new member otherwise gets the
 * connected car radio's Bluetooth name as a starting point. The field stays
 * editable, so users can type a model or registration plate instead.
 */
private fun rememberDefaultCarLabel(savedLabel: String, pairedCarName: String): String =
    savedLabel.ifBlank { pairedCarName.trim().take(60) }

@Composable
private fun FamilySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FamilyMemberRow(
    carLabel: String,
    deviceId: String,
    isYou: Boolean,
    spotAge: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.DirectionsCar,
            contentDescription = null,
            tint = Color(FamilyPalette.colorFor(deviceId)),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = carLabel + if (isYou) " (${stringResource(R.string.family_you)})" else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = spotAge ?: stringResource(R.string.family_no_spot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconButtonWithLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FamilyNameDialog(
    title: String,
    description: String?,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = name.isNotBlank() && name.length <= 60
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.family_display_name)) },
                    placeholder = { Text(stringResource(R.string.family_display_name_hint)) },
                    singleLine = true,
                    isError = !valid && name.isNotEmpty(),
                    supportingText = if (!valid) {
                        { Text(stringResource(R.string.family_name_required)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = valid,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FamilyJoinDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var invite by rememberSaveable { mutableStateOf("") }
    var carLabel by rememberSaveable { mutableStateOf(initialName) }
    val inviteValid = FamilyCrypto.parseInvite(invite) != null
    val labelValid = carLabel.isNotBlank() && carLabel.length <= 60
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it.take(256) },
                    label = { Text(stringResource(R.string.family_invite_link)) },
                    placeholder = { Text(stringResource(R.string.family_invite_link_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                    ),
                    isError = invite.isNotEmpty() && !inviteValid,
                )
                Text(
                    text = stringResource(R.string.family_join_link_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.family_car_label_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = carLabel,
                    onValueChange = { carLabel = it.take(60) },
                    label = { Text(stringResource(R.string.family_display_name)) },
                    placeholder = { Text(stringResource(R.string.family_display_name_hint)) },
                    singleLine = true,
                    isError = !labelValid && carLabel.isNotEmpty(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(invite, carLabel.trim()) },
                enabled = inviteValid && labelValid,
            ) {
                Text(stringResource(R.string.family_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FamilyServerDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    val valid = url.trim().startsWith("https://") && url.trim().length > 8
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.family_server_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.family_server_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.family_server_url)) },
                    placeholder = { Text(stringResource(R.string.family_server_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = !valid,
                    supportingText = if (!valid) {
                        { Text(stringResource(R.string.family_error_invalid_url)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun familyErrorText(error: FamilyError): String = stringResource(
    when (error) {
        FamilyError.NETWORK -> R.string.family_error_network
        FamilyError.INVALID_CODE -> R.string.family_error_invalid_code
        FamilyError.INVALID_INVITE -> R.string.family_error_invalid_invite
        FamilyError.UNAUTHORIZED -> R.string.family_error_unauthorized
        FamilyError.INVALID_NAME -> R.string.family_error_invalid_name
        FamilyError.INVALID_URL -> R.string.family_error_invalid_url
        FamilyError.NOT_CONNECTED -> R.string.family_error_not_connected
        FamilyError.RATE_LIMITED -> R.string.family_error_rate_limited
        FamilyError.GROUP_FULL -> R.string.family_error_group_full
        FamilyError.CRYPTO -> R.string.family_error_crypto
        FamilyError.PHOTO_LIMIT -> R.string.family_error_photo_limit
        FamilyError.PHOTO_DISABLED -> R.string.family_error_photo_disabled
        FamilyError.PHOTO_TOO_LARGE -> R.string.family_error_photo_too_large
        FamilyError.PHOTO_FAILED -> R.string.family_error_photo_failed
        FamilyError.NO_PHOTO -> R.string.family_error_no_photo
        FamilyError.NO_RECIPIENTS -> R.string.family_error_no_recipients
        FamilyError.SERVER -> R.string.family_error_server
        FamilyError.UNKNOWN -> R.string.family_error_unknown
    },
)

private fun copyToClipboard(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Parked invite code", code))
}
