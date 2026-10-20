package com.piko.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.piko.agent.core.PikoJson
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "piko_settings")

class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<PikoSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            val raw = preferences[key]
            if (raw.isNullOrBlank()) {
                PikoSettings()
            } else {
                runCatching { PikoJson.decodeFromString(PikoSettings.serializer(), raw) }
                    .getOrNull()
                    ?.sanitized()
                    ?: PikoSettings()
            }
        }

    suspend fun current(): PikoSettings = settings.first()

    suspend fun update(transform: (PikoSettings) -> PikoSettings) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[key]?.let { raw ->
                runCatching { PikoJson.decodeFromString(PikoSettings.serializer(), raw) }.getOrNull()
            } ?: PikoSettings()
            preferences[key] = PikoJson.encodeToString(
                PikoSettings.serializer(),
                transform(current).sanitized(),
            )
        }
    }

    suspend fun setProviderConfigs(configs: List<ProviderConfig>, selectedProviderId: String? = null) {
        update { settings ->
            settings.copy(
                providerConfigs = configs,
                selectedProviderId = selectedProviderId ?: settings.selectedProviderId,
            )
        }
    }

    suspend fun upsertProvider(provider: ProviderConfig, select: Boolean = false) {
        update { settings ->
            val existing = settings.providerConfigs.indexOfFirst { it.id == provider.id }
            val configs = settings.providerConfigs.toMutableList().apply {
                if (existing >= 0) set(existing, provider) else add(provider)
            }
            settings.copy(
                providerConfigs = configs,
                selectedProviderId = if (select) provider.id else settings.selectedProviderId,
            )
        }
    }

    suspend fun deleteProvider(providerId: String) {
        update { settings ->
            val remaining = settings.providerConfigs.filterNot { it.id == providerId }
            settings.copy(
                providerConfigs = remaining,
                selectedProviderId = if (settings.selectedProviderId == providerId) {
                    remaining.firstOrNull()?.id ?: ""
                } else {
                    settings.selectedProviderId
                },
            )
        }
    }

    suspend fun selectProvider(providerId: String) {
        update { it.copy(selectedProviderId = providerId) }
    }
}
