package dev.ophoner.device

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ophoner.MainActivity
import dev.ophoner.agent.*
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.data.repository.UiPrefsRepository
import dev.ophoner.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** One small, non-modal accessibility window; expansion pauses before the next action. */
class AgentOverlay(
    private val service: OphonerAccessibilityService,
    private val manager: AgentRunManager,
    private val approval: ActionApproval,
    private val settings: SettingsRepository,
    private val prefs: UiPrefsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val locked = MutableStateFlow(keyguard.isKeyguardLocked)
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            locked.value = intent?.action == Intent.ACTION_SCREEN_OFF || keyguard.isKeyguardLocked
        }
    }
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var run by mutableStateOf<AgentRunState?>(null)
    private var expanded by mutableStateOf(false)
    private var hiddenRun: String? = null
    private var selectedRun: String? = null
    private var captureHidden = false
    private var draft by mutableStateOf("")
    private val density get() = service.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()
    private val layout = WindowManager.LayoutParams(
        px(280), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = px(12); y = px(80); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    init {
        ContextCompat.registerReceiver(service, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(manager.runs, AppVisibility.visible, locked) { runs, foreground, isLocked -> runs to (foreground || isLocked) }.collect { (runs, foreground) ->
                val active = runs.values.filter { it.isRunning }
                val next = active.firstOrNull { it.conversationId == selectedRun }
                    ?: active.firstOrNull { it.activeToolCalls.any { call -> call.isExecuting && call.name in setOf("screen_control", "screen_capture", "phone_actions") } }
                    ?: active.lastOrNull()
                    ?: runs[selectedRun].takeIf { expanded && draft.isNotBlank() }
                if (next?.conversationId != selectedRun) {
                    selectedRun = next?.conversationId
                    draft = ""
                    expanded = false
                    hiddenRun = null
                }
                run = next
                if (next == null || foreground || hiddenRun == next.conversationId) removeView()
                else show()
            }
        }
    }

    private fun show() {
        if (view != null) return
        val lifecycle = OverlayOwner()
        owner = lifecycle
        val compose = ComposeView(service)
        view = compose
        compose.let {
            compose.setViewTreeLifecycleOwner(lifecycle)
            compose.setViewTreeSavedStateRegistryOwner(lifecycle)
            compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            compose.setContent {
                val theme by settings.observeThemeMode().collectAsState(initial = "SYSTEM")
                val font by settings.observeUiFont().collectAsState(initial = "DM_SANS")
                val accent by settings.observeAccent().collectAsState(initial = "BLUE")
                val uiPrefs by prefs.prefs.collectAsState(initial = UiPrefs())
                OphoneTheme(themeMode = runCatching { ThemeMode.valueOf(theme) }.getOrDefault(ThemeMode.SYSTEM), uiFont = UiFont.parse(font),
                    accent = runCatching { AccentChoice.valueOf(accent) }.getOrDefault(AccentChoice.BLUE), uiPrefs = uiPrefs) {
                    Panel()
                }
            }
            updateLayout()
            try {
                windows.addView(compose, layout)
                compose.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
                lifecycle.registry.currentState = Lifecycle.State.RESUMED
            } catch (_: Exception) {
                lifecycle.registry.currentState = Lifecycle.State.DESTROYED
                compose.disposeComposition()
                view = null
            }
        }
    }

    private fun updateLayout() {
        val screen = service.resources.displayMetrics
        layout.width = minOf(px(if (expanded) 380 else 280), screen.widthPixels - px(24))
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        layout.x = layout.x.coerceIn(0, (screen.widthPixels - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(px(32), (screen.heightPixels - px(if (expanded) 550 else 130)).coerceAtLeast(px(32)))
        view?.takeIf { it.isAttachedToWindow }?.let { windows.updateViewLayout(it, layout) }
    }

    private fun expand() {
        run?.let { manager.pauseRun(it.conversationId, true) }
        expanded = true
        layout.y = px(60)
        updateLayout()
    }

    private fun collapse(resume: Boolean) {
        if (resume) run?.let { manager.pauseRun(it.conversationId, false) }
        expanded = false
        updateLayout()
    }

    private fun openChat(id: String) {
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("conversation_id", id).putExtra("draft_text", draft.takeIf { it.isNotBlank() }))
        draft = ""
    }

    @Composable
    private fun Panel() {
        val state = run ?: return
        val t = tokens()
        val pending by approval.pending.collectAsState()
        val text = draft
        var error by remember(state.conversationId) { mutableStateOf<String?>(null) }
        var sending by remember { mutableStateOf(false) }
        Surface(shape = RoundedCornerShape(if (expanded) 24.dp else 28.dp), color = t.panel, contentColor = t.ink,
            border = androidx.compose.foundation.BorderStroke(1.dp, t.lineStrong), shadowElevation = 8.dp) {
            val panelModifier = if (expanded) Modifier.heightIn(max = (service.resources.displayMetrics.heightPixels / density - 100).coerceAtLeast(240f).dp).verticalScroll(rememberScrollState()) else Modifier
            Column(panelModifier.padding(if (expanded) 16.dp else 4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .pointerInput(Unit) { detectDragGestures { change, amount ->
                        change.consume(); layout.x += amount.x.toInt(); layout.y += amount.y.toInt(); updateLayout()
                    } }
                    .clickable(onClickLabel = if (expanded) "Collapse agent status" else "Expand agent status") { if (expanded) collapse(false) else expand() }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (state.pauseRequested) t.warning else t.accent, RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ophoner", style = MaterialTheme.typography.labelMedium, color = t.secondary)
                        Text(if (pending.isNotEmpty()) "An action needs approval" else state.statusLabel(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium)
                }
                if (!expanded) {
                    // A short excerpt, never raw tool arguments or internal reasoning.
                    state.streamingText.takeIf { it.isNotBlank() }?.let {
                        Text(it.takeLast(160), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = t.secondary, modifier = Modifier.padding(start = 30.dp, end = 12.dp, bottom = 8.dp))
                    }
                } else {
                    Text(if (!state.isRunning) "This run ended. Open chat to keep your draft and continue." else if (state.isPaused) "Paused while you review or type." else "Pausing before the next action. The current action may finish.",
                        style = MaterialTheme.typography.labelMedium, color = t.secondary, modifier = Modifier.padding(vertical = 8.dp))
                    val output = state.streamingText.ifBlank { state.lastOutput }.ifBlank { "Waiting for the agent's next update." }
                    SelectionContainer {
                        Text(output.takeLast(4000), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
                    }
                    if (pending.isNotEmpty()) TextButton(onClick = { openChat(state.conversationId) }) { Text("Open Ophoner to review approval") }
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= 50_000) draft = it }, label = { Text("Send a follow-up") },
                        placeholder = { Text("Add a detail or change direction…") }, minLines = 1, maxLines = 3, enabled = !sending,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                    if (state.queuedFollowUps > 0) Text("${state.queuedFollowUps} follow-up queued for the next model turn", style = MaterialTheme.typography.labelSmall, color = t.secondary)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.danger) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { if (draft.isNotBlank()) openChat(state.conversationId); manager.cancelRun(state.conversationId) }) { Text("Stop", color = t.danger) }
                        TextButton(onClick = { collapse(true) }, enabled = !sending) { Text("Resume") }
                        Button(enabled = state.isRunning && text.isNotBlank() && !sending, onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    if (manager.sendFollowUp(state.conversationId, text)) { draft = ""; error = null; collapse(true) }
                                    else error = "This run has ended. Open the chat to continue."
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "Couldn't save your follow-up." }
                                finally { sending = false }
                            }
                        }) { Text("Send") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { openChat(state.conversationId) }) { Text("Open chat") }
                        TextButton(onClick = { hiddenRun = state.conversationId; collapse(true); removeView() }) { Text("Hide pill") }
                    }
                }
            }
        }
    }

    /** Keep the agent's own status out of model screenshots. Called on the main thread. */
    fun hideForCapture(hide: Boolean) { captureHidden = hide; view?.visibility = if (hide) View.INVISIBLE else View.VISIBLE }

    fun avoid(bounds: Rect) {
        val current = view ?: return
        if (expanded) return
        val location = IntArray(2).also(current::getLocationOnScreen)
        val rect = Rect(location[0], location[1], location[0] + current.width, location[1] + current.height)
        if (Rect.intersects(rect, bounds)) {
            layout.y = if (bounds.centerY() < service.resources.displayMetrics.heightPixels / 2) service.resources.displayMetrics.heightPixels - px(160) else px(50)
            updateLayout()
        }
    }

    private fun removeView() {
        view?.let { if (it.isAttachedToWindow) windows.removeView(it); it.disposeComposition() }
        view = null
        owner?.registry?.currentState = Lifecycle.State.DESTROYED
        owner = null
    }

    fun destroy() { scope.cancel(); service.unregisterReceiver(lockReceiver); removeView() }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
}
