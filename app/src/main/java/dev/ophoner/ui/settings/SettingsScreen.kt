package dev.ophoner.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.ophoner.ui.components.GroupedSection
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import dev.ophoner.ui.theme.isDarkTheme

private val InstrumentCorner = RoundedCornerShape(10.dp)
private val InstrumentInnerCorner = RoundedCornerShape(7.dp)
private val Hairline = 0.5.dp

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

    var systemPromptDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val systemPromptValue = systemPromptDraft ?: uiState.systemPrompt
    val systemPromptDirty = systemPromptDraft != null && systemPromptDraft != uiState.systemPrompt

    LaunchedEffect(uiState.systemPrompt) {
        if (systemPromptDraft == uiState.systemPrompt) {
            systemPromptDraft = null
        }
    }

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
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                SectionHeader("Appearance")
                GroupedSection {
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
            }

            Column {
                SectionHeader("System Prompt")
                GroupedSection {
                    SystemPromptEditor(
                        value = systemPromptValue,
                        dirty = systemPromptDirty,
                        onValueChange = { systemPromptDraft = it },
                        onSave = { viewModel.setSystemPrompt(systemPromptValue) },
                        onRevert = { systemPromptDraft = null },
                    )
                }
                HelperText("Sent with every chat. Folders append their own scope instructions.")
            }

            Column {
                SectionHeader("LLM Providers")
                if (uiState.providers.isNotEmpty()) {
                    GroupedSection {
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
                    InstrumentButton(
                        text = "Add",
                        icon = Icons.Outlined.Add,
                        onClick = { showAddProvider = true },
                        modifier = Modifier.weight(1f),
                    )
                    InstrumentButton(
                        text = "Import",
                        icon = Icons.Outlined.FileDownload,
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                    )
                    InstrumentButton(
                        text = "Export",
                        icon = Icons.Outlined.FileUpload,
                        onClick = { exportLauncher.launch("ophoner_providers.json") },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.providers.isNotEmpty(),
                    )
                }
                HelperText("Backup or move your providers between devices.")
            }

            Column {
                SectionHeader("File Access")
                GroupedSection {
                    ChevronRow(
                        leading = Icons.Outlined.FolderOpen,
                        title = "Working Directory",
                        subtitle = uiState.workingDirDisplay ?: "Tap to select a folder",
                        onClick = { dirPickerLauncher.launch(null) },
                    )
                }
            }

            Column {
                SectionHeader("Agent")
                GroupedSection {
                    YoloModeRow(
                        enabled = uiState.yoloMode,
                        onToggle = viewModel::setYoloMode,
                    )
                }
                HelperText(
                    "Dangerous: YOLO auto-allows unscoped shell commands, skips confirmation " +
                        "gates, and raises the agent iteration limit. Hard denylist still applies. " +
                        "Only enable if you trust the model with broad device access.",
                )
            }

            Column {
                SectionHeader("Shell Privileges")
                GroupedSection {
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
            }

            Spacer(Modifier.height(20.dp))
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
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
    )
}

@Composable
private fun Separator() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        thickness = Hairline,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}

@Composable
private fun SystemPromptEditor(
    value: String,
    dirty: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            shape = InstrumentCorner,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dirty) {
                TextButton(onClick = onRevert) {
                    Text("Revert", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = onSave) {
                    Text("Save", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(
                    "Saved",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun YoloModeRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val warningTint = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = warningTint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "YOLO mode",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (enabled) "On — unscoped shell + no confirmations"
                else "Off — allowlisted shell only",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (enabled) warningTint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onError,
                checkedTrackColor = warningTint,
                checkedBorderColor = warningTint,
            ),
        )
    }
}

@Composable
private fun ChevronRow(
    leading: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            leading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
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
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ThemeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Contrast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Theme",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
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
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Tonality,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Accent",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                selected.displayName.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            .size(26.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (isSelected) 18.dp else 22.dp)
                .clip(CircleShape)
                .background(color),
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(Hairline * 3, color, CircleShape),
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
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "Interface Font",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
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
            .clip(InstrumentCorner)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                Hairline,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                InstrumentCorner,
            )
            .padding(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            for (opt in options) {
                val isSelected = opt == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(InstrumentInnerCorner)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.surface
                            else Color.Transparent
                        )
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    Hairline,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    InstrumentInnerCorner,
                                )
                            } else Modifier
                        )
                        .clickable { onSelect(opt) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(opt),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            letterSpacing = 0.2.sp,
                        ),
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
private fun InstrumentButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val borderAlpha = if (enabled) 0.4f else 0.2f
    Row(
        modifier = modifier
            .clip(InstrumentCorner)
            .background(
                if (enabled) MaterialTheme.colorScheme.surfaceContainerLow
                else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
            )
            .border(
                Hairline,
                MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha),
                InstrumentCorner,
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
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            ),
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
            .padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .then(
                    if (isActive) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(
                        Hairline * 3,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
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
                    modifier = Modifier.size(12.dp),
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
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(17.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(17.dp),
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = when (status) {
            ShizukuStatus.CONNECTED -> MaterialTheme.colorScheme.secondary
            ShizukuStatus.PERMISSION_NEEDED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Shizuku",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                status.displayLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.7.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
