package dev.ophoner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.ui.navigation.OphoneNavGraph
import dev.ophoner.ui.theme.AccentChoice
import dev.ophoner.ui.theme.OphoneTheme
import dev.ophoner.ui.theme.ThemeMode
import dev.ophoner.ui.theme.UiFont
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settingsRepository.observeThemeMode()
                .collectAsState(initial = "SYSTEM")
            val uiFont by settingsRepository.observeUiFont()
                .collectAsState(initial = "GEIST_SANS")
            val accent by settingsRepository.observeAccent()
                .collectAsState(initial = "BLUE")

            OphoneTheme(
                themeMode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                uiFont = runCatching { UiFont.valueOf(uiFont) }.getOrDefault(UiFont.GEIST_SANS),
                accent = runCatching { AccentChoice.valueOf(accent) }.getOrDefault(AccentChoice.BLUE),
            ) {
                val navController = rememberNavController()
                OphoneNavGraph(navController)
            }
        }
    }
}
