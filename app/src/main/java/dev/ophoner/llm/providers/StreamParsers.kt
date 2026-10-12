package dev.ophoner.llm.providers

import dev.ophoner.llm.LlmResponseChunk
import java.io.BufferedReader
import kotlinx.serialization.json.*

private val streamJson = Json { ignoreUnknownKeys = true }
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.tokens(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun requireStream(ok: Boolean, message: String) {
    if (!ok) throw StreamProtocolException(message)
}
private fun payload(data: String): JsonObject = streamJson.parseToJsonElement(data).jsonObject

internal suspend fun parseChatStream(reader: BufferedReader, emit: suspend (LlmResponseChunk) -> Unit) {
    val calls = linkedMapOf<Int, String>()
    var reason: String? = null
    var input: Long? = null
    var output: Long? = null
    var cacheRead: Long? = null
    var cacheCreation: Long? = null
    var complete = false
    readSse(reader) { data ->
        if (data == "[DONE]") {
            requireStream(reason in setOf("stop", "tool_calls"), "Stream ended without a successful finish reason: $reason")
            complete = true
            emit(LlmResponseChunk.Done(reason!!, input, output, cacheRead, cacheCreation))
            false
        } else {
            val event = payload(data)
            requireStream(event["error"] == null, "Provider returned a streaming error")
            event.obj("usage")?.let {
                input = it.tokens("prompt_tokens")
                output = it.tokens("completion_tokens")
                cacheRead = it.obj("prompt_tokens_details")?.tokens("cached_tokens")
            }
            val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            choice?.let {
                val delta = it.obj("delta")
                delta?.text("content")?.takeIf(String::isNotEmpty)?.let { text -> emit(LlmResponseChunk.TextDelta(text)) }
                delta?.get("tool_calls")?.jsonArray?.forEach { entry ->
                    requireStream(reason == null, "Tool delta after finish reason")
                    val call = entry.jsonObject
                    val index = call["index"]?.jsonPrimitive?.intOrNull ?: throw StreamProtocolException("Missing tool index")
                    val function = call.obj("function")
                    if (index !in calls) {
                        val id = call.text("id").orEmpty()
                        val name = function?.text("name").orEmpty()
                        requireStream(id.isNotBlank() && name.isNotBlank() && id !in calls.values, "Missing or duplicate tool identity")
                        calls[index] = id
                        emit(LlmResponseChunk.ToolCallStart(id, name))
                    }
                    function?.text("arguments")?.takeIf(String::isNotEmpty)?.let { args ->
                        emit(LlmResponseChunk.ToolCallArgumentDelta(calls.getValue(index), args))
                    }
                }
                it.text("finish_reason")?.let { finish ->
                    requireStream(reason == null, "Duplicate finish reason")
                    requireStream(finish in setOf("stop", "tool_calls"), "Model response stopped: $finish")
                    reason = finish
                    calls.values.forEach { id -> emit(LlmResponseChunk.ToolCallEnd(id)) }
                }
            }
            true
        }
    }
    if (!complete) throw StreamInterruptedException("Provider stream interrupted before [DONE]")
}

internal suspend fun parseClaudeStream(reader: BufferedReader, emit: suspend (LlmResponseChunk) -> Unit) {
    val calls = mutableMapOf<Int, String>()
    var reason: String? = null
    var input: Long? = null
    var output: Long? = null
    var cacheRead: Long? = null
    var cacheCreation: Long? = null
    var complete = false
    readSse(reader) { data ->
        val event = payload(data)
        when (event.text("type")) {
            "message_start" -> event.obj("message")?.obj("usage")?.let { usage ->
                cacheRead = usage.tokens("cache_read_input_tokens")
                cacheCreation = usage.tokens("cache_creation_input_tokens")
                input = usage.tokens("input_tokens")?.let { it + (cacheRead ?: 0) + (cacheCreation ?: 0) }
            }
            "content_block_start" -> {
                val block = event.obj("content_block") ?: throw StreamProtocolException("Missing content block")
                if (block.text("type") == "tool_use") {
                    val id = block.text("id").orEmpty()
                    val name = block.text("name").orEmpty()
                    val index = event["index"]?.jsonPrimitive?.intOrNull ?: throw StreamProtocolException("Missing block index")
                    requireStream(id.isNotBlank() && name.isNotBlank() && index !in calls, "Invalid tool identity")
                    calls[index] = id
                    emit(LlmResponseChunk.ToolCallStart(id, name))
                }
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: throw StreamProtocolException("Missing delta")
                when (delta.text("type")) {
                    "text_delta" -> emit(LlmResponseChunk.TextDelta(delta.text("text").orEmpty()))
                    "input_json_delta" -> {
                        val index = event["index"]?.jsonPrimitive?.intOrNull
                        val id = calls[index] ?: throw StreamProtocolException("Arguments for an unknown tool")
                        emit(LlmResponseChunk.ToolCallArgumentDelta(id, delta.text("partial_json").orEmpty()))
                    }
                }
            }
            "content_block_stop" -> calls.remove(event["index"]?.jsonPrimitive?.intOrNull)?.let { emit(LlmResponseChunk.ToolCallEnd(it)) }
            "message_delta" -> {
                reason = event.obj("delta")?.text("stop_reason") ?: reason
                output = event.obj("usage")?.tokens("output_tokens") ?: output
            }
            "message_stop" -> {
                requireStream(calls.isEmpty(), "Response ended with unfinished tools")
                requireStream(reason in setOf("end_turn", "tool_use", "stop_sequence"), "Model response stopped: $reason")
                complete = true
                emit(LlmResponseChunk.Done(reason!!, input, output, cacheRead, cacheCreation))
            }
            "error" -> throw StreamProtocolException("Provider returned a streaming error")
        }
        !complete
    }
    if (!complete) throw StreamInterruptedException("Provider stream interrupted before message_stop")
}

internal suspend fun parseResponsesStream(reader: BufferedReader, emit: suspend (LlmResponseChunk) -> Unit) {
    val calls = mutableMapOf<String, String>() // item ID -> call ID
    val arguments = mutableMapOf<String, StringBuilder>()
    val ended = mutableSetOf<String>()
    var complete = false
    readSse(reader) { data ->
        requireStream(data != "[DONE]", "Response stream ended before response.completed")
        val event = payload(data)
        when (val type = event.text("type")) {
            "response.output_text.delta" -> emit(LlmResponseChunk.TextDelta(event.text("delta").orEmpty()))
            "response.output_item.added" -> {
                val item = event.obj("item")
                if (item?.text("type") == "function_call") {
                    val id = item.text("id").orEmpty()
                    val callId = item.text("call_id").orEmpty()
                    val name = item.text("name").orEmpty()
                    requireStream(id.isNotBlank() && callId.isNotBlank() && name.isNotBlank() && id !in calls && callId !in calls.values, "Invalid tool identity")
                    calls[id] = callId
                    arguments[id] = StringBuilder()
                    emit(LlmResponseChunk.ToolCallStart(callId, name))
                }
            }
            "response.function_call_arguments.delta" -> {
                val id = event.text("item_id")
                val callId = calls[id] ?: throw StreamProtocolException("Arguments for an unknown tool")
                requireStream(id !in ended, "Arguments after tool completion")
                val delta = event.text("delta").orEmpty()
                arguments.getValue(id!!).append(delta)
                emit(LlmResponseChunk.ToolCallArgumentDelta(callId, delta))
            }
            "response.function_call_arguments.done" -> {
                val id = event.text("item_id")
                val callId = calls[id] ?: throw StreamProtocolException("Completion for an unknown tool")
                requireStream(id !in ended, "Duplicate tool completion")
                val finalArgs = event.text("arguments") ?: throw StreamProtocolException("Missing final tool arguments")
                requireStream(finalArgs == arguments.getValue(id!!).toString(), "Final tool arguments differ from streamed arguments")
                ended.add(id)
                emit(LlmResponseChunk.ToolCallEnd(callId))
            }
            "response.completed", "response.done" -> {
                val response = event.obj("response") ?: throw StreamProtocolException("Missing completed response")
                requireStream(response.text("status") == "completed", "Response status is not completed")
                requireStream(ended.containsAll(calls.keys), "Response ended with unfinished tools")
                val usage = response.obj("usage")
                emit(LlmResponseChunk.Done(
                    if (calls.isEmpty()) "stop" else "tool_calls",
                    usage?.tokens("input_tokens"), usage?.tokens("output_tokens"),
                    usage?.obj("input_tokens_details")?.tokens("cached_tokens"),
                ))
                complete = true
            }
            "response.failed", "response.incomplete", "response.error", "error" ->
                throw StreamProtocolException("Provider response did not complete: $type")
        }
        !complete
    }
    if (!complete) throw StreamInterruptedException("Provider stream interrupted before response.completed")
}
