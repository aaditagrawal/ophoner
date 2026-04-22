package dev.ophoner.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.tools.sandbox.SandboxedShell
import dev.ophoner.tools.sandbox.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val workingDirUri: String? = null,
    val workingDirDisplay: String? = null,
    val importExportMessage: String? = null,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val shell: SandboxedShell,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshShizukuStatus()

        viewModelScope.launch {
            combine(
                settingsRepository.observeProviders(),
                settingsRepository.observeActiveProviderId(),
                settingsRepository.observeWorkingDirectoryUri(),
            ) { providers, activeId, dirUri ->
                _uiState.value.copy(
                    providers = providers,
                    activeProviderId = activeId,
                    workingDirUri = dirUri,
                    workingDirDisplay = dirUri?.let { uri ->
                        Uri.parse(uri).lastPathSegment?.replace("primary:", "/")
                    },
                )
            }.collect { state -> _uiState.value = state }
        }
    }

    fun addProvider(config: ProviderConfig) {
        viewModelScope.launch {
            val current = _uiState.value.providers
            settingsRepository.saveProviders(current + config)
            // Auto-activate if first provider
            if (current.isEmpty()) {
                settingsRepository.setActiveProvider(config.id)
            }
        }
    }

    fun updateProvider(config: ProviderConfig) {
        viewModelScope.launch {
            val updated = _uiState.value.providers.map {
                if (it.id == config.id) config else it
            }
            settingsRepository.saveProviders(updated)
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            val updated = _uiState.value.providers.filter { it.id != id }
            settingsRepository.saveProviders(updated)
            if (_uiState.value.activeProviderId == id) {
                settingsRepository.setActiveProvider(updated.firstOrNull()?.id ?: "")
            }
        }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch {
            settingsRepository.setActiveProvider(id)
        }
    }

    fun setWorkingDirectory(uri: Uri) {
        viewModelScope.launch {
            // Take persistent permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settingsRepository.setWorkingDirectory(uri.toString())
        }
    }

    fun exportProviders(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.exportProviders(uri)
                .onSuccess { count ->
                    _uiState.update { it.copy(importExportMessage = "Exported $count provider(s)") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(importExportMessage = "Export failed: ${e.message}") }
                }
        }
    }

    fun importProviders(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.importProviders(uri)
                .onSuccess { count ->
                    val msg = if (count > 0) "Imported $count new provider(s)"
                    else "No new providers to import (all duplicates)"
                    _uiState.update { it.copy(importExportMessage = msg) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(importExportMessage = "Import failed: ${e.message}") }
                }
        }
    }

    fun clearImportExportMessage() {
        _uiState.update { it.copy(importExportMessage = null) }
    }

    fun refreshShizukuStatus() {
        _uiState.update { it.copy(shizukuStatus = shell.shizukuStatus()) }
    }

    fun requestShizukuPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(0)
        } catch (_: Exception) {
            // Shizuku not available
        }
    }
}
