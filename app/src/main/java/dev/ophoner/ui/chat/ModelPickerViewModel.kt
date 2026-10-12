package dev.ophoner.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ChatGptSpeed
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.models.CatalogModels
import dev.ophoner.llm.models.ProviderModelResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelPickerState(
    val providers: List<ProviderConfig> = emptyList(),
    val catalogs: Map<String, CatalogModels> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    val selectionError: String? = null,
    val selecting: Boolean = false,
)

@HiltViewModel
class ModelPickerViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val resolver: ProviderModelResolver,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelPickerState())
    val state = mutableState.asStateFlow()
    private val fetches = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch { settings.observeProviders().collect { providers ->
            mutableState.update { it.copy(providers = providers) }
        } }
    }

    fun refresh(provider: ProviderConfig) {
        fetches[provider.id]?.cancel()
        fetches[provider.id] = viewModelScope.launch {
            mutableState.update { it.copy(loading = it.loading + provider.id, errors = it.errors - provider.id) }
            try {
                val catalog = resolver.models(provider)
                mutableState.update { it.copy(catalogs = it.catalogs + (provider.id to catalog), loading = it.loading - provider.id) }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(errors = it.errors + (provider.id to (error.message ?: "Could not refresh models")), loading = it.loading - provider.id) }
            }
        }
    }

    fun selectSpeed(providerId: String, speed: ChatGptSpeed, onSelected: () -> Unit) {
        if (mutableState.value.selecting) return
        mutableState.update { it.copy(selecting = true, selectionError = null) }
        viewModelScope.launch {
            try {
                settings.setChatGptSpeed(providerId, speed)
                onSelected()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(selectionError = error.message ?: "Could not change speed") }
            } finally {
                mutableState.update { it.copy(selecting = false) }
            }
        }
    }

    fun select(providerId: String, modelId: String?, addSlug: Boolean = false, onSelected: () -> Unit) {
        if (mutableState.value.selecting) return
        mutableState.update { it.copy(selecting = true, selectionError = null) }
        viewModelScope.launch {
            try {
                val config = settings.selectModel(providerId, modelId, addSlug)
                if (config.useLatestModel) resolver.resolve(config)
                onSelected()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(selectionError = error.message ?: "Could not select model") }
                onSelected()
            } finally {
                mutableState.update { it.copy(selecting = false) }
            }
        }
    }
}
