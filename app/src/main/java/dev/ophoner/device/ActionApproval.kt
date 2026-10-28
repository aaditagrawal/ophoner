package dev.ophoner.device

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolPolicies
import dev.ophoner.tools.ToolPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal fun needsApproval(name: String, args: JsonObject): Boolean = ToolPolicies.forTool(name).needsApproval(args)

data class PendingAction(val id: String, val tool: String, val details: String)

@Singleton
class ActionApproval @Inject constructor() {
    private val answers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutable = MutableStateFlow<List<PendingAction>>(emptyList())
    val pending = mutable.asStateFlow()
    suspend fun approve(tool: String, args: JsonObject, policy: ToolPolicy = ToolPolicies.forTool(tool)): Boolean {
        if (currentCoroutineContext()[ToolExecutionContext]?.yoloMode == true || !policy.needsApproval(args)) return true
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Boolean>()
        answers[id] = answer
        mutable.update { it + PendingAction(id, tool, args.toString()) }
        return try { withTimeoutOrNull(120_000) { answer.await() } ?: false }
        finally { answers.remove(id); mutable.update { list -> list.filterNot { it.id == id } } }
    }
    fun respond(id: String, allow: Boolean) { answers[id]?.complete(allow) }
    fun denyAll() { answers.values.forEach { it.complete(false) } }
}

@Composable
fun ActionApprovalDialog(approval: ActionApproval) {
    val pending by approval.pending.collectAsState()
    val item = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { approval.respond(item.id, false) },
        title = { Text("Allow ${item.tool}?") },
        text = { Text(item.details, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { approval.respond(item.id, true) }) { Text("Run once") } },
        dismissButton = { TextButton(onClick = { approval.respond(item.id, false) }) { Text("Deny") } },
    )
}
