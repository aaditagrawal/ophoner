package dev.ophoner.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
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
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import dev.ophoner.ui.theme.isDarkTheme

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
                        style = MaterialTheme.typography.titleLarge,
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
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Appearance
            SectionHeader("Appearance")
            GroupedList {
                ThemeRow(
                    selected = uiState.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                Separator()
                AccentRow(
                    selected = uiState.accent,
                    onSelect = viewModel::setAccent,
                )
                Separator()
                FontRow(
                    selected = uiState.uiFont,
                    onSelect = viewModel::setUiFont,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Providers
            SectionHeader("LLM Providers")
            if (uiState.providers.isNotEmpty()) {
                GroupedList {
                    uiState.providers.forEachIndexed { index, provider ->
                        ProviderRow(
                            config = provider,
                            isActive = provider.id == uiState.activeProviderId,
                            onSetActive = { viewModel.setActiveProvider(provider.id) },
                            onEdit = { editingProviderId = provider.id },
                            onDelete = { viewModel.deleteProvider(provider.id) },
                        )
                        if (index < uiState.providers.lastIndex) Separator()
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillButton(
                    text = "Add",
                    icon = Icons.Outlined.Add,
                    onClick = { showAddProvider = true },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "Import",
                    icon = Icons.Outlined.Download,
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = "Export",
                    icon = Icons.Outlined.Upload,
                    onClick = { exportLauncher.launch("ophoner_providers.json") },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.providers.isNotEmpty(),
                )
            }
            HelperText("Backup or move your providers between devices.")

            Spacer(Modifier.height(24.dp))

            // Working directory
            SectionHeader("File Access")
            GroupedList {
                ChevronRow(
                    leading = Icons.Outlined.FolderOpen,
                    title = "Working Directory",
                    subtitle = uiState.workingDirDisplay ?: "Tap to select a folder",
                    onClick = { dirPickerLauncher.launch(null) },
                )
            }

            Spacer(Modifier.height(24.dp))

            // Shizuku
            SectionHeader("Shell Privileges")
            GroupedList {
                ShizukuRow(
                    status = uiState.shizukuStatus,
                    onGrant = viewModel::requestShizukuPermission,
                    onRefresh = viewModel::refreshShizukuStatus,
                )
            }
            HelperText(
                when (uiState.shizukuStatus) {
                    ShizukuStatus.CONNECTED -> "Shell commands run with ADB-level privileges."
                    ShizukuStatus.PERMISSION_NEEDED -> "Tap Grant to allow privileged shell access."
                    ShizukuStatus.NOT_RUNNING -> "Start the Shizuku app for ADB-level shell access."
                    ShizukuStatus.NOT_INSTALLED -> "Install Shizuku for ADB-level shell access."
                }
            )

            Spacer(Modifier.height(40.dp))
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
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 0.6.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
    )
}

@Composable
private fun GroupedList(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        content()
    }
}

@Composable
private fun Separator() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
}

@Composable
private fun ChevronRow(
    leading: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ThemeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Theme",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        SegmentedControl(
            options = ThemeMode.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.displayName },
        )
    }
}

@Composable
private fun AccentRow(
    selected: AccentChoice,
    onSelect: (AccentChoice) -> Unit,
) {
    val dark = isDarkTheme()
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ColorLens,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Accent",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                selected.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for (choice in AccentChoice.entries) {
                AccentSwatchDot(
                    color = choice.color(dark),
                    isSelected = choice == selected,
                    onClick = { onSelect(choice) },
                )
            }
        }
    }
}

@Composable
private fun AccentSwatchDot(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 22.dp else 24.dp)
                .clip(CircleShape)
                .background(color),
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .border(2.dp, color, CircleShape),
            )
        }
    }
}

@Composable
private fun FontRow(
    selected: UiFont,
    onSelect: (UiFont) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Interface Font",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        SegmentedControl(
            options = UiFont.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.displayName },
        )
    }
}

@Composable
private fun <T : Any> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (opt in options) {
                val isSelected = opt == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent
                        )
                        .clickable { onSelect(opt) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(opt),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSetActive)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (isActive) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        CircleShape,
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                config.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${config.providerType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} · ${config.modelId.split("/").lastOrNull() ?: config.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ShizukuRow(
    status: ShizukuStatus,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when (status) {
            ShizukuStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
            ShizukuStatus.PERMISSION_NEEDED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            "Shizuku · ${status.displayLabel}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (status == ShizukuStatus.PERMISSION_NEEDED) {
            TextButton(onClick = onGrant) {
                Text("Grant")
            }
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
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
