package com.bartixxx.parked.family

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.familyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parked_family",
)

/**
 * Family credentials live in a separate DataStore so tokens never end up in the
 * regular settings snapshot. The file is private to the app sandbox.
 */
class FamilyStore(private val context: Context) {

    val credentials: Flow<FamilyCredentials> = context.familyDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toCredentials() }

    suspend fun current(): FamilyCredentials = credentials.first()

    suspend fun saveSession(
        serverUrl: String,
        groupId: String,
        deviceId: String,
        deviceToken: String,
        adminToken: String?,
        inviteCode: String?,
        carLabel: String,
    ) {
        context.familyDataStore.edit { preferences ->
            preferences[Keys.ENABLED] = true
            preferences[Keys.SERVER_URL] = serverUrl
            preferences[Keys.GROUP_ID] = groupId
            preferences[Keys.DEVICE_ID] = deviceId
            preferences[Keys.DEVICE_TOKEN] = deviceToken
            preferences[Keys.INVITE_CODE] = inviteCode.orEmpty()
            preferences[Keys.CAR_LABEL] = carLabel
            if (adminToken.isNullOrBlank()) {
                preferences.remove(Keys.ADMIN_TOKEN)
            } else {
                preferences[Keys.ADMIN_TOKEN] = adminToken
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.familyDataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setServerUrl(url: String) {
        context.familyDataStore.edit { it[Keys.SERVER_URL] = url }
    }

    suspend fun setCarLabel(label: String) {
        context.familyDataStore.edit { it[Keys.CAR_LABEL] = label }
    }

    suspend fun setInviteCode(code: String) {
        context.familyDataStore.edit { it[Keys.INVITE_CODE] = code }
    }

    /**
     * Removes the group session but keeps the chosen server and the last car
     * label, so leaving a self-hosted group does not silently switch the next
     * group back to the public server.
     */
    suspend fun clear() {
        context.familyDataStore.edit { preferences ->
            val serverUrl = preferences[Keys.SERVER_URL]
            val carLabel = preferences[Keys.CAR_LABEL]
            preferences.clear()
            if (serverUrl != null) preferences[Keys.SERVER_URL] = serverUrl
            if (carLabel != null) preferences[Keys.CAR_LABEL] = carLabel
        }
    }

    private fun Preferences.toCredentials(): FamilyCredentials = FamilyCredentials(
        enabled = this[Keys.ENABLED] ?: false,
        serverUrl = this[Keys.SERVER_URL]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FAMILY_SERVER,
        groupId = this[Keys.GROUP_ID]?.takeIf { it.isNotBlank() },
        deviceId = this[Keys.DEVICE_ID]?.takeIf { it.isNotBlank() },
        deviceToken = this[Keys.DEVICE_TOKEN]?.takeIf { it.isNotBlank() },
        adminToken = this[Keys.ADMIN_TOKEN]?.takeIf { it.isNotBlank() },
        inviteCode = this[Keys.INVITE_CODE]?.takeIf { it.isNotBlank() },
        carLabel = this[Keys.CAR_LABEL].orEmpty(),
    )

    private object Keys {
        val ENABLED = booleanPreferencesKey("family_enabled")
        val SERVER_URL = stringPreferencesKey("family_server_url")
        val GROUP_ID = stringPreferencesKey("family_group_id")
        val DEVICE_ID = stringPreferencesKey("family_device_id")
        val DEVICE_TOKEN = stringPreferencesKey("family_device_token")
        val ADMIN_TOKEN = stringPreferencesKey("family_admin_token")
        val INVITE_CODE = stringPreferencesKey("family_invite_code")
        // Keep the legacy key name so upgrades from 1.2.0 keep the saved label.
        val CAR_LABEL = stringPreferencesKey("family_display_name")
    }
}
