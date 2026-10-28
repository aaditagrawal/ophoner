package dev.ophoner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.data.repository.PendingShareText
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.data.repository.UiPrefsRepository
import dev.ophoner.ui.navigation.OphoneNavGraph
import dev.ophoner.ui.navigation.Routes
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.OphoneTheme
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import dev.ophoner.ui.theme.UiPrefs
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var requestedConversation by mutableStateOf<String?>(null)
    private var requestedDraft: String? = null
    @Inject lateinit var actionApproval: dev.ophoner.device.ActionApproval
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var uiPrefsRepository: UiPrefsRepository
    @Inject lateinit var pendingShareText: PendingShareText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedDraft = intent?.getStringExtra("draft_text")
        requestedConversation = intent?.getStringExtra("conversation_id")
        handleShareIntent(intent, requestNavigation = false)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.observeThemeMode()
                .collectAsState(initial = "SYSTEM")
            val uiFont by settingsRepository.observeUiFont()
                .collectAsState(initial = "DM_SANS")
            val accent by settingsRepository.observeAccent()
                .collectAsState(initial = "BLUE")
            val uiPrefs by uiPrefsRepository.prefs
                .collectAsState(initial = UiPrefs())

            OphoneTheme(
                themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                uiFont = UiFont.parse(uiFont),
                accent = runCatching { AccentChoice.valueOf(accent) }.getOrDefault(AccentChoice.BLUE),
                uiPrefs = uiPrefs,
            ) {
                val navController = rememberNavController()
                LaunchedEffect(Unit) {
                    pendingShareText.navigateToNewChat.collect {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.CHAT) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                LaunchedEffect(requestedConversation) {
                    requestedConversation?.let { id ->
                        navController.navigate("chat/${android.net.Uri.encode(id)}") { launchSingleTop = true }
                        requestedDraft?.let { navController.currentBackStackEntry?.savedStateHandle?.set("promptDraft", it) }
                        requestedDraft = null
                        requestedConversation = null
                    }
                }
                OphoneNavGraph(navController)
                dev.ophoner.device.ActionApprovalDialog(actionApproval)
            }
        }
    }

    override fun onStart() { super.onStart(); dev.ophoner.agent.AppVisibility.visible.value = true }
    override fun onStop() { dev.ophoner.agent.AppVisibility.visible.value = false; super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDraft = intent.getStringExtra("draft_text")
        requestedConversation = intent.getStringExtra("conversation_id")
        handleShareIntent(intent, requestNavigation = true)
    }

    private fun handleShareIntent(intent: Intent?, requestNavigation: Boolean) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type
        // Some senders omit MIME type or only put text in ClipData.
        if (type != null && !type.startsWith("text/") && type != "*/*") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.let { clip ->
                buildString {
                    for (i in 0 until clip.itemCount) {
                        val itemText = clip.getItemAt(i)?.coerceToText(this@MainActivity)?.toString()
                        if (!itemText.isNullOrBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(itemText)
                        }
                    }
                }.ifBlank { null }
            }
            ?: return
        pendingShareText.offer(text, requestNavigation = requestNavigation)
    }
}
