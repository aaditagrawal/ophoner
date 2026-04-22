package dev.ophoner.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.tools.sandbox.ShizukuStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddProvider by rememberSaveable { mutableStateOf(false) }
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.setWorkingDirectory(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportProviders(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importProviders(it) }
    }

    // Show snackbar when import/export message is set
    LaunchedEffect(uiState.importExportMessage) {
        uiState.importExportMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearImportExportMessage()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Providers section
            SectionHeader("LLM Providers")

            for (provider in uiState.providers) {
                ProviderCard(
                    config = provider,
                    isActive = provider.id == uiState.activeProviderId,
                    onSetActive = { viewModel.setActiveProvider(provider.id) },
                    onEdit = { editingProviderId = provider.id },
                    onDelete = { viewModel.deleteProvider(provider.id) },
                )
                Spacer(Modifier.height(8.dp))
            }

            TextButton(
                onClick = { showAddProvider = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Provider")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = { exportLauncher.launch("ophoner_providers.json") },
                    enabled = uiState.providers.isNotEmpty(),
                ) {
                    Text("Export Providers")
                }
                TextButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                ) {
                    Text("Import Providers")
                }
            }

            Spacer(Modifier.height(24.dp))

            // Working directory
            SectionHeader("Working Directory")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { dirPickerLauncher.launch(null) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "File Access Directory",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        uiState.workingDirDisplay ?: "Tap to select a folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Shizuku section
            SectionHeader("Shell Privileges (Shizuku)")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusColor = when (uiState.shizukuStatus) {
                    ShizukuStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                    ShizukuStatus.PERMISSION_NEEDED -> MaterialTheme.colorScheme.error
                    ShizukuStatus.NOT_RUNNING -> MaterialTheme.colorScheme.outline
                    ShizukuStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Shizuku: ${uiState.shizukuStatus.displayLabel}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        when (uiState.shizukuStatus) {
                            ShizukuStatus.CONNECTED -> "Shell commands run with ADB-level privileges"
                            ShizukuStatus.PERMISSION_NEEDED -> "Tap Grant to allow privileged shell access"
                            ShizukuStatus.NOT_RUNNING -> "Start Shizuku app for ADB-level shell access"
                            ShizukuStatus.NOT_INSTALLED -> "Install Shizuku for ADB-level shell access"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (uiState.shizukuStatus == ShizukuStatus.PERMISSION_NEEDED) {
                    TextButton(onClick = {
                        viewModel.requestShizukuPermission()
                    }) {
                        Text("Grant")
                    }
                }
                TextButton(onClick = { viewModel.refreshShizukuStatus() }) {
                    Text("Refresh")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAddProvider) {
        ProviderDialog(
            existing = null,
            onDismiss = { showAddProvider = false },
            onSubmit = { config ->
                viewModel.addProvider(config)
                showAddProvider = false
            },
        )
    }

    editingProviderId?.let { id ->
        val existing = uiState.providers.firstOrNull { it.id == id }
        if (existing != null) {
            ProviderDialog(
                existing = existing,
                onDismiss = { editingProviderId = null },
                onSubmit = { config ->
                    viewModel.updateProvider(config)
                    editingProviderId = null
                },
            )
        } else {
            editingProviderId = null
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onSetActive() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                config.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                "${config.providerType.name} | ${config.modelId.split("/").lastOrNull() ?: config.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(
    existing: ProviderConfig?,
    onDismiss: () -> Unit,
    onSubmit: (ProviderConfig) -> Unit,
) {
    val isEdit = existing != null
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.displayName ?: "") }
    var apiKey by rememberSaveable(existing?.id) { mutableStateOf(existing?.apiKey ?: "") }
    var baseUrl by rememberSaveable(existing?.id) { mutableStateOf(existing?.baseUrl ?: "") }
    var modelId by rememberSaveable(existing?.id) { mutableStateOf(existing?.modelId ?: "") }
    var selectedType by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.providerType ?: ProviderType.CUSTOM_OPENAI)
    }
    var typeExpanded by remember { mutableStateOf(false) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Provider" else "Add Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. Kimi Turbo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Provider type dropdown
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType.name.replace("_", " "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        ProviderType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.replace("_", " ")) },
                                onClick = {
                                    selectedType = type
                                    typeExpanded = false
                                    // Auto-fill base URL
                                    baseUrl = when (type) {
                                        ProviderType.CLAUDE -> "https://api.anthropic.com"
                                        ProviderType.OPENAI -> "https://api.openai.com/v1"
                                        ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
                                        else -> baseUrl
                                    }
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(if (showApiKey) "hide" else "show", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. gpt-4o, claude-sonnet-4-20250514") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && apiKey.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank()) {
                        onSubmit(ProviderConfig(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            displayName = name,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            modelId = modelId,
                            providerType = selectedType,
                        ))
                    }
                },
                enabled = name.isNotBlank() && apiKey.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank(),
            ) {
                Text(if (isEdit) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
