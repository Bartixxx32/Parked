package io.github.bartixxx32.parked.data.trigger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.triggerStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "parked_trigger_state",
)

/**
 * Persists [TriggerState] across process death and device reboots.
 */
class TriggerStateRepository(private val context: Context) {

    val state: Flow<TriggerState> = context.triggerStateDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toTriggerState() }

    suspend fun current(): TriggerState = state.first()

    suspend fun save(triggerState: TriggerState) {
        context.triggerStateDataStore.edit { preferences ->
            preferences[Keys.PHASE] = triggerState.phase.name
            preferences[Keys.CYCLE_ID] = triggerState.cycleId
            if (triggerState.deviceAddress.isNullOrBlank()) {
                preferences.remove(Keys.DEVICE_ADDRESS)
            } else {
                preferences[Keys.DEVICE_ADDRESS] = triggerState.deviceAddress
            }
            preferences[Keys.PENDING_AT] = triggerState.pendingAtEpochMs
            preferences[Keys.LAST_AUTO_SAVE_AT] = triggerState.lastAutoSaveAtEpochMs
        }
    }

    suspend fun clear() {
        context.triggerStateDataStore.edit { preferences ->
            preferences.remove(Keys.PHASE)
            preferences.remove(Keys.CYCLE_ID)
            preferences.remove(Keys.DEVICE_ADDRESS)
            preferences.remove(Keys.PENDING_AT)
            preferences.remove(Keys.LAST_AUTO_SAVE_AT)
        }
    }

    private fun Preferences.toTriggerState(): TriggerState = TriggerState(
        phase = this[Keys.PHASE]
            ?.let { stored -> runCatching { TriggerPhase.valueOf(stored) }.getOrNull() }
            ?: TriggerPhase.UNARMED,
        cycleId = this[Keys.CYCLE_ID] ?: 0L,
        deviceAddress = this[Keys.DEVICE_ADDRESS]?.takeIf { it.isNotBlank() },
        pendingAtEpochMs = this[Keys.PENDING_AT] ?: 0L,
        lastAutoSaveAtEpochMs = this[Keys.LAST_AUTO_SAVE_AT] ?: 0L,
    )

    private object Keys {
        val PHASE = stringPreferencesKey("phase")
        val CYCLE_ID = longPreferencesKey("cycle_id")
        val DEVICE_ADDRESS = stringPreferencesKey("device_address")
        val PENDING_AT = longPreferencesKey("pending_at_epoch_ms")
        val LAST_AUTO_SAVE_AT = longPreferencesKey("last_auto_save_at_epoch_ms")
    }
}
