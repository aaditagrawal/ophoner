package dev.ophoner.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import dev.ophoner.data.model.AuthMode
import dev.ophoner.data.model.OAuthTokens
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderPresets
import dev.ophoner.data.model.ProviderType
import dev.ophoner.tools.sandbox.ShizukuStatus
import dev.ophoner.ui.components.CardCorner
import dev.ophoner.ui.components.GlassIconButton
import dev.ophoner.ui.components.GroupedSection
import dev.ophoner.ui.components.LocalGlassState
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.LucideIcon
import dev.ophoner.ui.components.PrimaryButton
import dev.ophoner.ui.components.RowDivider
import dev.ophoner.ui.components.ScreenTitle
import dev.ophoner.ui.components.SecondaryButton
import dev.ophoner.ui.components.SectionLabel
import dev.ophoner.ui.components.StatusBarScrim
import dev.ophoner.ui.components.glassSource
import dev.ophoner.ui.components.pressFade
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import dev.ophoner.ui.theme.isDarkTheme
import dev.ophoner.ui.theme.monoFamily
import dev.ophoner.ui.theme.tokens
import java.util.UUID
import kotlinx.coroutines.delay

/** Row icon column (16 padding + 20 icon + 16 gap) — dividers start here. */
private val RowTextIndent = 52.dp

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCapabilities: () -> Unit = {},
    onOpenDeviceSetup: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPlanPicker by rememberSaveable { mutableStateOf(false) }
    var draftPlanId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var signOutProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val glass = rememberHazeState()
    val t = tokens()

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

    LaunchedEffect(uiState.authMessage) {
        uiState.authMessage?.let { msg ->
            if (msg.startsWith("Signed in")) {
                showPlanPicker = false
                draftPlanId = null
                editingProviderId = null
            }
            snackbarHostState.showSnackbar(msg)
            viewModel.clearAuthMessage()
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalGlassState provides glass) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(t.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource(glass)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, top = statusBarTop + 76.dp, bottom = navBarBottom + 40.dp),
                verticalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                ScreenTitle(
                    title = "Settings",
                    subtitle = "Providers, appearance, and how far the agent can reach.",
                )

                SettingsSection("Providers") {
                    val chatGpt = uiState.providers.firstOrNull {
                        it.providerType == ProviderType.CODEX_CHATGPT
                    }
                    val chatGptConnected = chatGpt?.id in uiState.connectedChatGptIds
                    if (chatGptConnected && chatGpt != null) {
                        GroupedSection {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("ChatGPT", color = t.ink, style = MaterialTheme.typography.titleMedium)
                                        Text("Signed in", color = t.success, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { editingProviderId = chatGpt.id }) { Text("Manage") }
                                }
                                uiState.chatGptEmails[chatGpt.id]?.let { email ->
                                    Text(email, color = t.ink, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    chatGpt.modelId.ifBlank { "Choosing newest model…" } + if (chatGpt.useLatestModel) " · Auto" else "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()),
                                    color = t.secondary,
                                )
                                if (chatGpt.id != uiState.activeProviderId) {
                                    TextButton(onClick = { viewModel.setActiveProvider(chatGpt.id) }) { Text("Use for chat") }
                                }
                            }
                        }
                    } else {
                        PrimaryButton(
                            text = "Log in with ChatGPT",
                            icon = Lucide.MessageSquare,
                            onClick = viewModel::loginWithChatGpt,
                            enabled = uiState.deviceLogin == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HelperText(
                            "Use your ChatGPT account for Codex models. No API key needed.",
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val otherProviders = uiState.providers.filterNot { chatGptConnected && it.id == chatGpt?.id }
                    if (otherProviders.isNotEmpty()) {
                        GroupedSection {
                            otherProviders.forEachIndexed { index, provider ->
                                ProviderRow(
                                    config = provider,
                                    isActive = provider.id == uiState.activeProviderId,
                                    onSetActive = { viewModel.setActiveProvider(provider.id) },
                                    onEdit = { editingProviderId = provider.id },
                                    onDelete = { viewModel.deleteProvider(provider.id) },
                                )
                                if (index < otherProviders.lastIndex) RowDivider(startIndent = RowTextIndent + 2.dp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    SecondaryButton(
                        text = "Add provider",
                        icon = Lucide.Plus,
                        onClick = { showPlanPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            text = "Import",
                            icon = Lucide.Download,
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Export",
                            icon = Lucide.Upload,
                            onClick = { exportLauncher.launch("ophoner_providers.json") },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.providers.isNotEmpty(),
                        )
                    }
                    HelperText("Back up or move your providers between devices.")
                }

                SettingsSection("Appearance") {
                    GroupedSection {
                        ThemeRow(
                            selected = uiState.themeMode,
                            onSelect = viewModel::setThemeMode,
                        )
                        RowDivider(startIndent = RowTextIndent)
                        AccentRow(
                            selected = uiState.accent,
                            onSelect = viewModel::setAccent,
                        )
                        RowDivider(startIndent = RowTextIndent)
                        FontRow(
                            selected = uiState.uiFont,
                            onSelect = viewModel::setUiFont,
                        )
                    }
                }

                SettingsSection("Personalize") {
                    PersonalizeSection()
                }

                SettingsSection("System prompt") {
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

                SettingsSection("File access") {
                    GroupedSection {
                        ChevronRow(
                            leading = Lucide.FolderOpen,
                            title = "Working directory",
                            subtitle = uiState.workingDirDisplay ?: "Tap to select a folder",
                            onClick = { dirPickerLauncher.launch(null) },
                        )
                    }
                }

                SettingsSection("Agent") {
                    GroupedSection {
                        ChevronRow(leading = Lucide.Smartphone, title = "Set up device access",
                            subtitle = "Recommended · Enable all with guided setup", onClick = onOpenDeviceSetup)
                    }
                    GroupedSection {
                        ChevronRow(
                            leading = Lucide.Smartphone,
                            title = "Device capabilities",
                            subtitle = "Permissions, app control, and connection tests",
                            onClick = onOpenCapabilities,
                        )
                    }
                    GroupedSection {
                        YoloModeRow(
                            enabled = uiState.yoloMode,
                            onToggle = viewModel::setYoloMode,
                        )
                    }
                    HelperText(
                        "Dangerous: YOLO auto-allows unscoped shell commands, skips confirmation " +
                            "gates. Hard denylist still applies. " +
                            "Only enable if you trust the model with broad device access.",
                    )
                }

                SettingsSection("Shell privileges") {
                    GroupedSection {
                        ChevronRow(leading = Lucide.Shield, title = "Set up Shizuku",
                            subtitle = "Guided connection · No root required", onClick = onOpenShizuku)
                    }
                }
            }

            StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))

            GlassIconButton(
                icon = Lucide.ArrowLeft,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(20.dp),
                    containerColor = t.ink,
                    contentColor = t.inverse,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }

    if (showPlanPicker && draftPlanId == null) {
        PlanPickerDialog(
            onDismiss = { showPlanPicker = false },
            onSelectPlan = { planId ->
                draftPlanId = planId
            },
            onSkipToCustom = {
                draftPlanId = ""
            },
        )
    }

    if (showPlanPicker && draftPlanId != null) {
        val plan = draftPlanId?.takeIf { it.isNotEmpty() }?.let { id ->
            ProviderPresets.ALL.find { it.id == id }
        }
        ProviderDialog(
            existing = null,
            plan = plan,
            openRouterPkcePending = uiState.openRouterPkcePending,
            onDismiss = {
                viewModel.cancelDeviceLogin()
                viewModel.clearOpenRouterPkce()
                draftPlanId = null
                showPlanPicker = false
            },
            onSubmit = { config ->
                viewModel.addProvider(config)
                draftPlanId = null
                showPlanPicker = false
            },
            onOpenUrl = viewModel::openUrl,
            onBeginCodexLogin = viewModel::beginCodexDeviceLogin,
            onBeginOpenRouterLogin = {
                val url = viewModel.beginOpenRouterLogin()
                url?.let(viewModel::openUrl)
            },
            onCompleteOpenRouterLogin = viewModel::completeOpenRouterLogin,
        )
    }

    editingProviderId?.let { id ->
        val existing = uiState.providers.firstOrNull { it.id == id }
        if (existing != null) {
            ProviderDialog(
                existing = existing,
                plan = ProviderPresets.forType(existing.providerType),
                chatGptConnected = existing.id in uiState.connectedChatGptIds,
                chatGptEmail = uiState.chatGptEmails[existing.id],
                chatGptTokens = remember(existing.id, uiState.providers) { viewModel.chatGptTokens(existing.id) },
                onSignOut = { editingProviderId = null; signOutProviderId = existing.id },
                    openRouterPkcePending = uiState.openRouterPkcePending,
                onDismiss = {
                    viewModel.cancelDeviceLogin()
                    viewModel.clearOpenRouterPkce()
                    editingProviderId = null
                },
                onSubmit = { config ->
                    viewModel.updateProvider(config)
                    editingProviderId = null
                },
                                onOpenUrl = viewModel::openUrl,
                onBeginCodexLogin = viewModel::beginCodexDeviceLogin,
                onBeginOpenRouterLogin = {
                    val url = viewModel.beginOpenRouterLogin()
                    url?.let(viewModel::openUrl)
                },
                onCompleteOpenRouterLogin = viewModel::completeOpenRouterLogin,
            )
        } else {
            editingProviderId = null
        }
    }

    uiState.deviceLogin?.let { deviceLogin ->
        DeviceLoginDialog(
            state = deviceLogin,
            onOpenVerification = { viewModel.openUrl(deviceLogin.verificationUrl) },
            onCancel = viewModel::cancelDeviceLogin,
            onRetry = viewModel::retryDeviceLogin,
        )
    }

    signOutProviderId?.let { id ->
        AlertDialog(
            onDismissRequest = { signOutProviderId = null },
            title = { Text("Sign out of ChatGPT?") },
            text = { Text("This removes the ChatGPT connection and its saved credentials from this device. Your conversations stay here.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.signOutChatGpt(id)
                    signOutProviderId = null
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { signOutProviderId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp),
        color = tokens().secondary,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
    )
}

/** Standard menu row: 20 dp line icon, 16 sp title, optional quiet subtitle. */
@Composable
internal fun RowHeader(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = tokens().accent,
    subtitleColor: Color = tokens().secondary,
    trailing: @Composable () -> Unit = {},
) {
    val t = tokens()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(icon = icon, contentDescription = null, tint = iconTint, size = 20.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = t.ink,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SystemPromptEditor(
    value: String,
    dirty: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
) {
    val t = tokens()
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = monoFamily(),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = t.accent,
                unfocusedBorderColor = t.line,
                focusedContainerColor = t.raised,
                unfocusedContainerColor = t.raised,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dirty) {
                TextButton(onClick = onRevert) {
                    Text("Revert", style = MaterialTheme.typography.labelLarge, color = t.secondary)
                }
                PrimaryButton(text = "Save", onClick = onSave, height = 40.dp)
            } else {
                Text(
                    "Saved",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.secondary,
                    modifier = Modifier.padding(end = 6.dp, top = 6.dp, bottom = 6.dp),
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
    val t = tokens()
    RowHeader(
        icon = Lucide.TriangleAlert,
        title = "YOLO mode",
        subtitle = if (enabled) "On: unscoped shell, no confirmations" else "Off: allowlisted shell only",
        iconTint = if (enabled) t.danger else t.icon,
        subtitleColor = if (enabled) t.danger else t.secondary,
        modifier = Modifier
            .pressFade(role = Role.Switch, onClick = { onToggle(!enabled) })
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = t.inverse,
                checkedTrackColor = t.danger,
                checkedBorderColor = t.danger,
                uncheckedThumbColor = t.mutedIcon,
                uncheckedTrackColor = t.panelHigh,
                uncheckedBorderColor = t.lineStrong,
            ),
        )
    }
}

@Composable
private fun ChevronRow(
    @DrawableRes leading: Int,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    RowHeader(
        icon = leading,
        title = title,
        subtitle = subtitle,
        modifier = Modifier
            .heightIn(min = 60.dp)
            .pressFade(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        LucideIcon(icon = Lucide.ChevronRight, contentDescription = null, tint = tokens().mutedIcon, size = 18.dp)
    }
}

@Composable
private fun ThemeRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        RowHeader(icon = Lucide.Contrast, title = "Theme")
        Spacer(Modifier.height(12.dp))
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
    val t = tokens()
    val dark = isDarkTheme()
    Column(modifier = Modifier.padding(top = 14.dp, bottom = 12.dp)) {
        RowHeader(
            icon = Lucide.Palette,
            title = "Accent",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        ) {
            Text(
                selected.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = t.secondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (choice in AccentChoice.entries) {
                AccentSwatchDot(
                    color = choice.color(dark),
                    label = choice.displayName,
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
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val t = tokens()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .pressFade(onClickLabel = label, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .border(2.dp, t.ink.copy(alpha = 0.85f), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, t.line, CircleShape),
        )
    }
}

@Composable
private fun FontRow(
    selected: UiFont,
    onSelect: (UiFont) -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        RowHeader(icon = Lucide.Type, title = "Interface font")
        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            options = UiFont.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.displayName },
        )
    }
}

/** Capsule segmented control; the selected segment is a raised pill with a hairline edge. */
@Composable
internal fun <T : Any> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    val t = tokens()
    val track = RoundedCornerShape(22.dp)
    val pill = RoundedCornerShape(19.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(track)
            .background(t.panelHigh)
            .padding(3.dp),
    ) {
        for (opt in options) {
            val isSelected = opt == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(pill)
                    .then(
                        if (isSelected) {
                            Modifier
                                .background(t.raised)
                                .border(1.dp, t.line, pill)
                        } else {
                            Modifier
                        },
                    )
                    .pressFade(role = Role.Tab, onClick = { onSelect(opt) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(opt),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) t.ink else t.secondary,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
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
    val t = tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .pressFade(role = Role.RadioButton, onClickLabel = "Use ${config.displayName}", onClick = onSetActive)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .then(
                    if (isActive) Modifier.background(t.accent)
                    else Modifier.border(1.5.dp, t.lineStrong, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isActive) {
                LucideIcon(icon = Lucide.Check, contentDescription = "Active", tint = t.onAccent, size = 14.dp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                config.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                providerRowSubtitle(config),
                style = MaterialTheme.typography.labelMedium,
                color = t.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowIconButton(icon = Lucide.Pencil, contentDescription = "Edit", tint = t.icon, onClick = onEdit)
        RowIconButton(icon = Lucide.Trash, contentDescription = "Delete", tint = t.danger, onClick = onDelete)
    }
}

@Composable
private fun RowIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .pressFade(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon = icon, contentDescription = contentDescription, tint = tint, size = 18.dp)
    }
}

private fun providerRowSubtitle(config: ProviderConfig): String {
    val planOrAuth = when {
        config.planLabel.isNotBlank() && config.authMode == AuthMode.OAUTH_DEVICE ->
            "${config.planLabel} · device"
        config.planLabel.isNotBlank() -> config.planLabel
        config.authMode == AuthMode.OAUTH_DEVICE -> "device"
        config.authMode == AuthMode.OAUTH_PKCE -> "PKCE"
        else -> config.providerType.name.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
    val selected = config.modelId.substringAfterLast('/')
    val model = if (config.useLatestModel) {
        selected.takeIf { it.isNotBlank() }?.let { "$it (auto)" } ?: "Newest available"
    } else selected
    return if (model.isNotBlank()) "$planOrAuth · $model" else planOrAuth
}

@Composable
private fun PlanPickerDialog(
    onDismiss: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onSkipToCustom: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Choose a plan preset, or continue with a blank custom provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderPresets.ALL.forEach { plan ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardCorner)
                            .background(tokens().panel)
                            .border(1.dp, tokens().line, CardCorner)
                            .pressFade(onClick = { onSelectPlan(plan.id) })
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            plan.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens().ink,
                        )
                        Text(
                            plan.subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens().secondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSkipToCustom) { Text("Custom") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeviceLoginDialog(
    state: DeviceLoginUi,
    onOpenVerification: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember(state.expiresAtEpochMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAtEpochMs, state.busy) {
        val expiry = state.expiresAtEpochMs ?: return@LaunchedEffect
        while (state.busy && now < expiry) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    fun copyCode() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ChatGPT login code", state.userCode))
    }
    AlertDialog(
        onDismissRequest = { if (!state.saving) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Log in with ChatGPT") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.userCode.isNotBlank() && state.error == null && !state.saving) {
                    Text("Copy this code, open ChatGPT, then enter it to approve the connection.")
                    SelectionContainer {
                        Text(
                            state.userCode,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = monoFamily(),
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = ::copyCode) { Text("Copy code") }
                    state.expiresAtEpochMs?.let { expiry ->
                        val secondsLeft = ((expiry - now + 999) / 1000).coerceAtLeast(0)
                        Text(
                            "Code expires in ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}. We'll keep checking while you sign in.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Return here after approval. If prompted, enable device code login in ChatGPT Settings > Security. Workspace accounts may need admin approval.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            when {
                                state.saving -> "Saving your connection…"
                                state.networkStatus != null -> "Waiting for connection…"
                                state.userCode.isBlank() -> "Getting your login code…"
                                else -> "Waiting for approval in ChatGPT…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.networkStatus?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
                if (state.userCode.isNotBlank() && state.error == null && !state.saving) {
                    TextButton(onClick = onOpenVerification) { Text("Open in external browser") }
                }
                state.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                TextButton(
                    onClick = {
                        copyCode()
                        runCatching {
                            CustomTabsIntent.Builder().setShowTitle(true).build()
                                .launchUrl(context, Uri.parse(state.verificationUrl))
                        }.onFailure { onOpenVerification() }
                    },
                    enabled = state.verificationUrl.isNotBlank() && !state.saving,
                ) { Text("Copy & open ChatGPT") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(
    existing: ProviderConfig?,
    plan: ProviderPresets.Plan?,
    openRouterPkcePending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (ProviderConfig) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBeginCodexLogin: (ProviderConfig) -> Unit,
    onBeginOpenRouterLogin: () -> Unit,
    onCompleteOpenRouterLogin: (code: String, draft: ProviderConfig) -> Unit,
    chatGptConnected: Boolean = false,
    chatGptEmail: String? = null,
    chatGptTokens: OAuthTokens? = null,
    onSignOut: () -> Unit = {},
) {
    val isEdit = existing != null
    val initialType = existing?.providerType ?: plan?.providerType ?: ProviderType.CUSTOM_OPENAI
    val initialAuth = existing?.authMode ?: plan?.authMode ?: AuthMode.API_KEY

    var name by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.displayName ?: plan?.defaultDisplayName.orEmpty())
    }
    var apiKey by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.apiKey.orEmpty())
    }
    var baseUrl by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.baseUrl ?: plan?.defaultBaseUrl.orEmpty())
    }
    var modelId by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.modelId ?: plan?.defaultModelId.orEmpty())
    }
    var selectedType by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(initialType)
    }
    var authMode by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(initialAuth)
    }
    var accountId by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.accountId.orEmpty())
    }
    var planLabel by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.planLabel ?: plan?.planLabel.orEmpty())
    }
    var customSlugs by remember(existing?.id, plan?.id) {
        mutableStateOf(existing?.customModelSlugs.orEmpty())
    }
    var useLatestModel by rememberSaveable(existing?.id, plan?.id) {
        mutableStateOf(existing?.useLatestModel ?: true)
    }
    var openRouterCode by rememberSaveable(existing?.id, plan?.id) { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    val providerId = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }

    fun draftConfig(): ProviderConfig = (existing ?: ProviderConfig(
        id = providerId, displayName = name, baseUrl = baseUrl, modelId = modelId, providerType = selectedType,
    )).copy(
        id = providerId,
        displayName = name,
        apiKey = apiKey,
        baseUrl = baseUrl,
        modelId = modelId,
        providerType = selectedType,
        authMode = authMode,
        accountId = accountId,
        customModelSlugs = customSlugs,
        planLabel = planLabel,
        useLatestModel = useLatestModel,
    )

    val oauthDeviceAuthed = authMode == AuthMode.OAUTH_DEVICE && chatGptConnected
    val signedInChatGpt = selectedType == ProviderType.CODEX_CHATGPT && oauthDeviceAuthed
    val canSubmit = name.isNotBlank() &&
        baseUrl.isNotBlank() &&
        (apiKey.isNotBlank() || oauthDeviceAuthed)

    val activePlan = plan?.takeIf { it.providerType == selectedType }
        ?: ProviderPresets.forType(selectedType)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signedInChatGpt) "ChatGPT" else if (isEdit) "Edit provider" else "Add provider") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (signedInChatGpt) {
                    Text("Signed in", color = tokens().success, style = MaterialTheme.typography.titleMedium)
                    Text(chatGptEmail ?: "Email unavailable", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
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
                            label = { Text("Provider type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
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
                                        useLatestModel = true
                                        typeExpanded = false
                                        val defaults = ProviderPresets.forType(type)
                                        if (defaults != null) {
                                            if (baseUrl.isBlank() || !isEdit) {
                                                baseUrl = defaults.defaultBaseUrl
                                            }
                                            if (modelId.isBlank() || !isEdit) {
                                                modelId = defaults.defaultModelId
                                            }
                                            if (name.isBlank() || name == plan?.defaultDisplayName) {
                                                name = defaults.defaultDisplayName
                                            }
                                            authMode = defaults.authMode
                                            planLabel = defaults.planLabel
                                            if (customSlugs.isEmpty()) {
                                                customSlugs = emptyList()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (selectedType == ProviderType.CLAUDE) {
                        Text(
                            "Claude.ai subscription login is not supported in third-party apps. " +
                                "Use an Anthropic Console API key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val consoleUrl = activePlan?.consoleUrl.orEmpty()
                        if (consoleUrl.isNotBlank()) {
                            TextButton(onClick = { onOpenUrl(consoleUrl) }) {
                                Text("Open Console API keys")
                            }
                        }
                    }

                    if (selectedType == ProviderType.CODEX_CHATGPT ||
                        activePlan?.supportsDeviceLogin == true
                    ) {
                        TextButton(
                            onClick = {
                                authMode = AuthMode.OAUTH_DEVICE
                                onBeginCodexLogin(draftConfig())
                            },
                        ) {
                            Text(
                                if (oauthDeviceAuthed) "Re-sign in with ChatGPT"
                                else "Sign in with ChatGPT",
                            )
                        }
                        if (oauthDeviceAuthed) {
                            Text(
                                "Signed in · account ${accountId.take(8)}…",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = monoFamily(),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (selectedType == ProviderType.OPENROUTER ||
                        activePlan?.supportsPkceLogin == true
                    ) {
                        TextButton(onClick = onBeginOpenRouterLogin) {
                            Text("Sign in with OpenRouter")
                        }
                        if (openRouterPkcePending) {
                            OutlinedTextField(
                                value = openRouterCode,
                                onValueChange = { openRouterCode = it },
                                label = { Text("Authorization code") },
                                placeholder = { Text("Paste code from OpenRouter") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = {
                                    onCompleteOpenRouterLogin(openRouterCode, draftConfig())
                                },
                                enabled = openRouterCode.isNotBlank(),
                            ) {
                                Text("Complete OpenRouter sign-in")
                            }
                        }
                    }

                    val keyHint = activePlan?.apiKeyHint.orEmpty()
                    if (selectedType == ProviderType.OPENCODE_GO) {
                        TextButton(onClick = { onOpenUrl("https://opencode.ai/auth") }) { Text("Get OpenCode Go API key") }
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = {
                            Text(
                                if (oauthDeviceAuthed ||
                                    (selectedType == ProviderType.CODEX_CHATGPT &&
                                        authMode == AuthMode.OAUTH_DEVICE)
                                ) {
                                    "API key (optional)"
                                } else {
                                    "API key"
                                },
                            )
                        },
                        placeholder = {
                            if (keyHint.isNotBlank()) Text(keyHint)
                        },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showApiKey = !showApiKey }) {
                                Text(
                                    if (showApiKey) "hide" else "show",
                                    style = MaterialTheme.typography.labelSmall,
                                )
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
                }

                if (signedInChatGpt) {
                    chatGptTokens?.let { ChatGptTokenSection(it) }
                    TextButton(onClick = onSignOut) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (signedInChatGpt) onDismiss()
                    else if (canSubmit) onSubmit(draftConfig())
                },
                enabled = canSubmit,
            ) {
                Text(if (signedInChatGpt) "Done" else if (isEdit) "Save" else "Connect")
            }
        },
        dismissButton = {
            if (!signedInChatGpt) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChatGptTokenSection(credentials: OAuthTokens) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().pressFade(onClick = { expanded = !expanded })
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auth tokens", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            LucideIcon(
                icon = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Hide auth tokens" else "Show auth tokens",
                tint = tokens().secondary,
                size = 18.dp,
            )
        }
        if (expanded) {
            AuthTokenField("Access token", credentials.accessToken)
            if (credentials.expiresAtEpochMs > 0) {
                val expiry = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(credentials.expiresAtEpochMs))
                Text("Expires $expiry · refreshes automatically", style = MaterialTheme.typography.bodySmall)
            }
            AuthTokenField("Refresh token", credentials.refreshToken)
            AuthTokenField("ID token", credentials.idToken)
        }
    }
}

@Composable
private fun AuthTokenField(label: String, value: String) {
    val context = LocalContext.current
    var revealed by remember(value) { mutableStateOf(false) }
    var copied by remember(value) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (value.isNotBlank()) {
                TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(label, value)
                    clip.description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    clipboard.setPrimaryClip(clip)
                    copied = true
                }) { Text(if (copied) "Copied" else "Copy") }
            }
        }
        if (revealed) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(if (value.isBlank()) "Not provided" else "••••••••••••", color = tokens().secondary)
        }
    }
}
