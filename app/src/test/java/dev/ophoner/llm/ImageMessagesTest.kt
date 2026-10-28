package dev.ophoner.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageMessagesTest {
    private val content = listOf(LlmContentBlock.Text("screen"), LlmContentBlock.Image("image/jpeg", "YWJj"))
    @Test fun `chat payload preserves image and text`() {
        val result = imageMessageContent(content, "chat")
        assertEquals("screen", result[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("image_url", result[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/jpeg;base64,YWJj", result[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content)
    }
    @Test fun `responses uses input image payload`() {
        val result = imageMessageContent(content, "responses")
        assertEquals("input_text", result[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", result[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("data:image/jpeg;base64,YWJj", result[1].jsonObject["image_url"]?.jsonPrimitive?.content)
    }
    @Test fun `anthropic uses base64 source object`() {
        val result = imageMessageContent(content, "anthropic")
        val source = result[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("YWJj", source["data"]?.jsonPrimitive?.content)
    }
}
