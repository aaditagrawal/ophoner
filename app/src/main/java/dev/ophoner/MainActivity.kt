package dev.ophoner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.data.repository.PendingShareText
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.ui.navigation.OphoneNavGraph
import dev.ophoner.ui.navigation.Routes
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.OphoneTheme
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var pendingShareText: PendingShareText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent, requestNavigation = false)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.observeThemeMode()
                .collectAsState(initial = "SYSTEM")
            val uiFont by settingsRepository.observeUiFont()
                .collectAsState(initial = "DM_MONO")
            val accent by settingsRepository.observeAccent()
                .collectAsState(initial = "ORANGE")

            OphoneTheme(
                themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                uiFont = UiFont.parse(uiFont),
                accent = runCatching { AccentChoice.valueOf(accent) }.getOrDefault(AccentChoice.ORANGE),
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
                OphoneNavGraph(navController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
