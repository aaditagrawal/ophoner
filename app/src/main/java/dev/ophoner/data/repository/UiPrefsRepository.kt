package dev.ophoner.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.ui.theme.UiPrefs
import dev.ophoner.ui.theme.parseEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiPrefsStore by preferencesDataStore(name = "ophoner_ui_prefs")

/** Personalisation (mascot, backgrounds, glass, text size), kept apart from agent settings. */
@Singleton
class UiPrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mascotEnabledKey = booleanPreferencesKey("mascot_enabled")
    private val mascotStyleKey = stringPreferencesKey("mascot_style")
    private val homeBackgroundKey = stringPreferencesKey("home_background")
    private val glassKey = stringPreferencesKey("glass_style")
    private val textSizeKey = stringPreferencesKey("text_size")
    private val showShortcutsKey = booleanPreferencesKey("show_shortcuts")

    val prefs: Flow<UiPrefs> = context.uiPrefsStore.data
        .map { it.toUiPrefs() }
        .distinctUntilChanged()

    suspend fun update(transform: (UiPrefs) -> UiPrefs) {
        context.uiPrefsStore.edit { store ->
            val next = transform(store.toUiPrefs())
            store[mascotEnabledKey] = next.mascotEnabled
            store[mascotStyleKey] = next.mascotStyle.name
            store[homeBackgroundKey] = next.homeBackground.name
            store[glassKey] = next.glass.name
            store[textSizeKey] = next.textSize.name
            store[showShortcutsKey] = next.showShortcuts
        }
    }

    private fun Preferences.toUiPrefs(): UiPrefs {
        val defaults = UiPrefs()
        return UiPrefs(
            mascotEnabled = this[mascotEnabledKey] ?: defaults.mascotEnabled,
            mascotStyle = parseEnum(this[mascotStyleKey], defaults.mascotStyle),
            homeBackground = parseEnum(this[homeBackgroundKey], defaults.homeBackground),
            glass = parseEnum(this[glassKey], defaults.glass),
            textSize = parseEnum(this[textSizeKey], defaults.textSize),
            showShortcuts = this[showShortcutsKey] ?: defaults.showShortcuts,
        )
    }
}
