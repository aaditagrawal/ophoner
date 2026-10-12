package dev.ophoner.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ophoner.data.repository.UiPrefsRepository
import dev.ophoner.ui.components.GroupedSection
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.Mascot
import dev.ophoner.ui.components.MascotFace
import dev.ophoner.ui.components.RowDivider
import dev.ophoner.ui.components.pressFade
import dev.ophoner.ui.theme.GlassStyle
import dev.ophoner.ui.theme.HomeBackground
import dev.ophoner.ui.theme.MascotStyle
import dev.ophoner.ui.theme.TextSize
import dev.ophoner.ui.theme.UiPrefs
import dev.ophoner.ui.theme.tokens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalizeViewModel @Inject constructor(
    private val repository: UiPrefsRepository,
) : ViewModel() {
    val prefs: StateFlow<UiPrefs> = repository.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiPrefs())

    fun update(transform: (UiPrefs) -> UiPrefs) {
        viewModelScope.launch { repository.update(transform) }
    }
}

private val PreviewFaces = MascotFace.entries.filter { it != MascotFace.Open }

/** Row indent matching Settings rows (16 padding + 20 icon + 16 gap). */
private val TextIndent = 52.dp

@Composable
fun PersonalizeSection(viewModel: PersonalizeViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    GroupedSection {
        MascotPreview(prefs)
        RowDivider(startIndent = 16.dp)
        ToggleRow(
            icon = Lucide.Sparkles,
            title = "Mascot",
            subtitle = "Blinks on the home screen and acts out every tool call",
            checked = prefs.mascotEnabled,
            onCheckedChange = { on -> viewModel.update { it.copy(mascotEnabled = on) } },
        )
        if (prefs.mascotEnabled) {
            RowDivider(startIndent = TextIndent)
            ChoiceRow(
                icon = Lucide.Palette,
                title = "Mascot style",
                options = MascotStyle.entries,
                selected = prefs.mascotStyle,
                label = { it.displayName },
                onSelect = { style -> viewModel.update { it.copy(mascotStyle = style) } },
            )
        }
        RowDivider(startIndent = TextIndent)
        ChoiceRow(
            icon = Lucide.Image,
            title = "Home background",
            options = HomeBackground.entries,
            selected = prefs.homeBackground,
            label = { it.displayName },
            onSelect = { bg -> viewModel.update { it.copy(homeBackground = bg) } },
        )
        RowDivider(startIndent = TextIndent)
        ChoiceRow(
            icon = Lucide.Contrast,
            title = "Controls",
            subtitle = "Solid skips the blur: sharper and lighter on battery",
            options = GlassStyle.entries,
            selected = prefs.glass,
            label = { it.displayName },
            onSelect = { glass -> viewModel.update { it.copy(glass = glass) } },
        )
        RowDivider(startIndent = TextIndent)
        ChoiceRow(
            icon = Lucide.Type,
            title = "Text size",
            options = TextSize.entries,
            selected = prefs.textSize,
            label = { it.displayName },
            onSelect = { size -> viewModel.update { it.copy(textSize = size) } },
        )
        RowDivider(startIndent = TextIndent)
        ToggleRow(
            icon = Lucide.LayoutGrid,
            title = "Home shortcuts",
            subtitle = "Web, Files and Device suggestions above the composer",
            checked = prefs.showShortcuts,
            onCheckedChange = { on -> viewModel.update { it.copy(showShortcuts = on) } },
        )
    }
}

@Composable
private fun MascotPreview(prefs: UiPrefs) {
    val t = tokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val color = if (prefs.mascotEnabled) t.accent else t.mutedIcon
        Mascot(size = 64.dp, face = MascotFace.Open, color = color)
        for (row in PreviewFaces.chunked(5)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (face in row) {
                    Mascot(size = 36.dp, face = face, color = color, blink = false)
                }
            }
        }
        Text(
            text = if (prefs.mascotEnabled) "Follows your accent colour" else "Mascot hidden",
            style = MaterialTheme.typography.labelMedium,
            color = t.secondary,
        )
    }
}

@Composable
private fun ToggleRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = tokens()
    RowHeader(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier
            .pressFade(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = t.onAccent,
                checkedTrackColor = t.accent,
                checkedBorderColor = t.accent,
                uncheckedThumbColor = t.mutedIcon,
                uncheckedTrackColor = t.panelHigh,
                uncheckedBorderColor = t.lineStrong,
            ),
        )
    }
}

@Composable
private fun <T : Any> ChoiceRow(
    @DrawableRes icon: Int,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        RowHeader(icon = icon, title = title, subtitle = subtitle)
        Spacer(Modifier.height(12.dp))
        SegmentedControl(options = options, selected = selected, onSelect = onSelect, label = label)
    }
}
