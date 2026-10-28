package dev.ophoner.llm

import kotlinx.serialization.json.*

internal fun imageMessageContent(content: List<LlmContentBlock>, format: String): JsonArray = buildJsonArray {
    for (block in content) when (block) {
        is LlmContentBlock.Text -> add(buildJsonObject {
            put("type", if (format == "responses") "input_text" else "text")
            put("text", block.text)
        })
        is LlmContentBlock.Image -> add(buildJsonObject {
            when (format) {
                "anthropic" -> {
                    put("type", "image")
                    putJsonObject("source") { put("type", "base64"); put("media_type", block.mediaType); put("data", block.base64) }
                }
                "responses" -> { put("type", "input_image"); put("image_url", "data:${block.mediaType};base64,${block.base64}") }
                else -> { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:${block.mediaType};base64,${block.base64}") } }
            }
        })
        else -> Unit
    }
}
