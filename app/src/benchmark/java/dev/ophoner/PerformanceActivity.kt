package dev.ophoner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import androidx.room.withTransaction
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.rememberHazeState
import dev.ophoner.data.db.OphoneDatabase
import dev.ophoner.data.entity.ConversationEntity
import dev.ophoner.data.entity.MessageEntity
import dev.ophoner.data.model.*
import dev.ophoner.ui.chat.ChatTimeline
import dev.ophoner.ui.chat.ChatUiState
import dev.ophoner.ui.components.*
import dev.ophoner.ui.navigation.OphoneNavGraph
import dev.ophoner.ui.navigation.Routes
import dev.ophoner.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Compiled only into the separate .benchmark application. No user data or API calls. */
@AndroidEntryPoint
class PerformanceActivity : ComponentActivity() {
    @Inject lateinit var database: OphoneDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val streaming = intent.getBooleanExtra("streaming", false)
        val solid = intent.getBooleanExtra("solid", false)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { seed() }
            setContent {
                OphoneTheme(uiPrefs = UiPrefs(glass = if (solid) GlassStyle.SOLID else GlassStyle.FROSTED)) {
                    if (streaming) StreamingFixture() else {
                        val nav = rememberNavController()
                        OphoneNavGraph(nav)
                        LaunchedEffect(Unit) { nav.navigate(Routes.CONVERSATIONS) }
                    }
                }
            }
        }
    }

    private suspend fun seed() {
        if (database.conversationDao().getById("perf-a") != null) return
        database.withTransaction {
            for ((id, title) in listOf("perf-a" to "Perf chat A", "perf-b" to "Perf chat B")) {
                database.conversationDao().insert(ConversationEntity(id, title, "fixture", createdAt = 1, updatedAt = 2))
                database.messageDao().insertAll(List(1_000) { index ->
                    MessageEntity("$id-$index", id, "ASSISTANT", Json.encodeToString<List<ContentBlock>>(
                        listOf(ContentBlock.Text(fixtureText(index))),
                    ), index, 1)
                })
            }
            repeat(300) { index ->
                database.conversationDao().insert(ConversationEntity(
                    "project-$index", "Project chat $index", "fixture", "folder",
                    "content://benchmark/tree/project", "Perf project", 1, 1,
                ))
            }
        }
    }
}

private fun fixtureText(index: Int) = "Perf message $index\n\n" +
    "A **formatted** message with `code`, *emphasis*, and enough text to wrap on a phone. ".repeat(4)

@Composable
private fun StreamingFixture() {
    val glass = rememberHazeState()
    val state = remember {
        mutableStateOf(ChatUiState(
            conversationId = "stream", historyLoaded = true, isAgentRunning = true,
            messages = List(50) { i -> Message("stream-$i", "stream", MessageRole.ASSISTANT,
                listOf(ContentBlock.Text(fixtureText(i))), i, 1) },
        ))
    }
    LaunchedEffect(Unit) {
        val text = StringBuilder()
        repeat(1_200) { i ->
            text.append("Streaming **chunk $i** with `code`.\n\n")
            state.value = state.value.copy(streamingText = text.toString())
            delay(50)
        }
    }
    CompositionLocalProvider(LocalGlassState provides glass) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().glassSource(glass)) {
                ChatTimeline(state, PaddingValues(top = 96.dp, bottom = 112.dp, start = 18.dp, end = 18.dp))
            }
            GlassIconButton(Lucide.PanelLeft, "Benchmark control", {}, Modifier.align(Alignment.TopStart).padding(top = 40.dp))
            GlassSurface(Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.Center) { Text("Streaming benchmark") }
        }
    }
}
