package dev.ophoner.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.LucideIcon
import dev.ophoner.ui.theme.tokens

/** The composer owns model selection; connection settings only own credentials. */
@Composable
fun ModelPicker(
    selected: ProviderConfig?,
    enabled: Boolean,
    onSelected: () -> Unit,
    onManageProviders: () -> Unit,
    onBusyChange: (Boolean) -> Unit,
    viewModel: ModelPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var expandedProvider by remember { mutableStateOf<String?>(null) }
    var addingFor by remember { mutableStateOf<ProviderConfig?>(null) }
    var newSlug by remember { mutableStateOf("") }
    val t = tokens()
    val focusManager = LocalFocusManager.current
    val menuHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp.coerceIn(160.dp, 420.dp)
    LaunchedEffect(state.selecting) { onBusyChange(state.selecting) }

    fun choose(providerId: String, model: String?, add: Boolean = false) {
        viewModel.select(providerId, model, add) {
            onSelected()
            if (viewModel.state.value.selectionError == null) {
                expanded = false
                addingFor = null
            }
        }
    }

    Box {
        TextButton(
            enabled = enabled && !state.selecting,
            onClick = {
                focusManager.clearFocus()
                if (state.providers.isEmpty()) onManageProviders()
                else {
                    expanded = true
                    val provider = state.providers.firstOrNull { it.id == selected?.id } ?: state.providers.first()
                    expandedProvider = provider.id
                    viewModel.refresh(provider)
                }
            },
            modifier = Modifier.widthIn(max = 260.dp).heightIn(min = 44.dp),
        ) {
            Text(
                when {
                    state.selecting -> "Updating…"
                    selected == null -> "Choose model"
                    selected.modelId.isBlank() -> "Automatic newest"
                    selected.useLatestModel -> "Auto · ${selected.modelId}"
                    else -> selected.modelId
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) t.ink else t.secondary,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(6.dp))
            LucideIcon(Lucide.ChevronDown, contentDescription = "Choose provider and model", tint = t.secondary, size = 16.dp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (!state.selecting) expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            // DropdownMenu queries intrinsic height. A lazy list needs an exact bound here.
            LazyColumn(Modifier.width(300.dp).height(menuHeight)) {
                item {
                    Text("Provider & model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp))
                    state.selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                state.providers.forEach { provider ->
                    item(key = "provider-${provider.id}") {
                        DropdownMenuItem(
                            text = { Text(provider.displayName, style = MaterialTheme.typography.titleSmall) },
                            trailingIcon = { LucideIcon(if (expandedProvider == provider.id) Lucide.ChevronDown else Lucide.ChevronRight,
                                contentDescription = null, tint = t.secondary, size = 16.dp) },
                            onClick = {
                                expandedProvider = if (expandedProvider == provider.id) null else provider.id
                                if (expandedProvider != null) viewModel.refresh(provider)
                            },
                        )
                    }
                    if (expandedProvider == provider.id) {
                        item(key = "auto-${provider.id}") {
                            DropdownMenuItem(
                                text = { Text("Automatic newest") },
                                leadingIcon = { RadioButton(selected = selected?.id == provider.id && selected.useLatestModel, onClick = null) },
                                enabled = !state.selecting,
                                onClick = { choose(provider.id, null) },
                            )
                            if (provider.id in state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            val message = state.errors[provider.id] ?: state.catalogs[provider.id]?.warning
                            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
                        }
                        val models = (state.catalogs[provider.id]?.ids.orEmpty() + provider.customModelSlugs + listOf(provider.modelId))
                            .filter { it.isNotBlank() }.distinct()
                        items(models, key = { "${provider.id}:$it" }) { model ->
                            DropdownMenuItem(
                                text = { Text(model, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { RadioButton(selected = selected?.id == provider.id && !selected.useLatestModel && selected.modelId == model, onClick = null) },
                                enabled = !state.selecting,
                                onClick = { choose(provider.id, model) },
                            )
                        }
                        item(key = "add-${provider.id}") {
                            DropdownMenuItem(text = { Text("Add model slug…") }, onClick = {
                                addingFor = provider
                                newSlug = ""
                                expanded = false
                            })
                            DropdownMenuItem(text = { Text("Refresh models") }, onClick = { viewModel.refresh(provider) }, enabled = provider.id !in state.loading)
                        }
                    }
                }
                item { DropdownMenuItem(text = { Text("Manage providers…") }, onClick = { expanded = false; onManageProviders() }) }
            }
        }
    }
    addingFor?.let { provider ->
        AlertDialog(
            onDismissRequest = { if (!state.selecting) addingFor = null },
            title = { Text("Add model slug") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(provider.displayName)
                    OutlinedTextField(value = newSlug, onValueChange = { newSlug = it }, label = { Text("Model slug") }, singleLine = true)
                    state.selectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = newSlug.isNotBlank() && !state.selecting,
                onClick = { choose(provider.id, newSlug.trim(), true) }) { Text("Add & select") } },
            dismissButton = { TextButton(onClick = { addingFor = null }, enabled = !state.selecting) { Text("Cancel") } },
        )
    }
}
