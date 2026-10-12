package dev.ophoner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.ophoner.agent.*
import dev.ophoner.data.model.*
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.di.ApplicationScope
import dev.ophoner.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import javax.inject.Inject

/** Offline interaction fixture, compiled ONLY into the separate benchmark app. */
@AndroidEntryPoint
class AgentInteractionActivity : ComponentActivity() {
    @Inject lateinit var conversations: ConversationRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var manager: AgentRunManager
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) { ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) }
            scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && !server.isClosed) {
                        val client = server.accept()
                        launch {
                            client.use { socket ->
                                try {
                                    socket.soTimeout = 5000
                                    val input = socket.getInputStream().buffered()
                                    fun readLine(): String {
                                        val line = StringBuilder()
                                        while (true) { val byte = input.read(); if (byte < 0) error("EOF"); if (byte == 10) return line.toString().trimEnd('\r'); line.append(byte.toChar()); require(line.length < 8192) }
                                    }
                                    var length = 0
                                    while (true) {
                                        val line = readLine()
                                        if (line.isEmpty()) break
                                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                                    }
                                    require(length in 1..1_000_000)
                                    val body = ByteArray(length)
                                    var offset = 0
                                    while (offset < length) { val read = input.read(body, offset, length - offset); if (read < 0) return@launch; offset += read }
                                    val messages = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["messages"]!!.jsonArray
                                    val users = messages.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                                    val output = socket.getOutputStream().bufferedWriter()
                                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                                    fun chunk(text: String) {
                                        val data = buildJsonObject {
                                            putJsonArray("choices") { add(buildJsonObject {
                                                put("index", 0); putJsonObject("delta") { put("content", text) }
                                            }) }
                                        }
                                        output.write("data: $data\n\n"); output.flush()
                                    }
                                    chunk(if (users > 1) "Follow-up received. This offline demo made no device changes. " else "Offline demo: showing live progress without making device changes. ")
                                    repeat(if (users > 1) 3 else 120) { index -> delay(500); chunk("Update ${index + 1}. ") }
                                    output.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                                    output.flush()
                                } catch (_: Exception) { /* The fixture client may cancel or retry. */ }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            scope.launch { delay(180_000); withContext(Dispatchers.IO) { server.close() } }
            val config = ProviderConfig("interaction-fixture", "Offline interaction demo", baseUrl = "http://127.0.0.1:${server.localPort}",
                modelId = "fixture", providerType = ProviderType.CUSTOM_OPENAI, useLatestModel = false)
            settings.saveProviders(settings.observeProviders().first().filterNot { it.id == config.id } + config)
            settings.setActiveProvider(config.id)
            val conversation = conversations.createConversation(config.id)
            conversations.updateTitle(conversation.id, "Interaction demo")
            val prompt = "Help me plan a trip. Keep working while I add details."
            conversations.saveMessage(Message(UUID.randomUUID().toString(), conversation.id, MessageRole.USER,
                listOf(ContentBlock.Text(prompt)), 0, System.currentTimeMillis()))
            AgentService.start(this@AgentInteractionActivity)
            manager.startRun(conversation.id, listOf(LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text(prompt)))), config)
            startActivity(Intent(this@AgentInteractionActivity, MainActivity::class.java).putExtra("conversation_id", conversation.id))
            finish()
        }
    }
}
