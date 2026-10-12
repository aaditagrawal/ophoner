package dev.ophoner.llm.models

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.auth.ProviderCredentialResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Resolve automatic selection before showing or using a provider in chat. */
@Singleton
class ProviderModelResolver @Inject constructor(
    private val catalog: ModelCatalogService,
    private val credentials: ProviderCredentialResolver,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun models(config: ProviderConfig): CatalogModels {
        val fetchConfig = if (config.providerType == ProviderType.CODEX_CHATGPT) {
            val (token, account) = credentials.bearerCredential(config)
            config.copy(apiKey = token, accountId = account.orEmpty())
        } else config
        return catalog.fetchModels(fetchConfig).getOrThrow()
    }

    suspend fun resolve(config: ProviderConfig): ProviderConfig {
        if (!config.useLatestModel) return config
        return mutex.withLock {
            val model = try {
                models(config).ids.firstOrNull()
                    ?: error("No compatible models are available for this provider.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (config.modelId.isBlank()) {
                    throw IllegalStateException("Could not choose a model. Check your connection or select a model below the prompt.", error)
                }
                // Offline launches can keep using the last successfully selected model.
                return@withLock config
            }
            coroutineContext.ensureActive()
            settings.updateAutomaticModel(config, model)
                ?: error("This provider was removed. Select another provider in Settings.")
        }
    }
}
