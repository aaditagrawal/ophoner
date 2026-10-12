package dev.ophoner.llm.providers

import dev.ophoner.llm.LlmResponseChunk
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamParsersTest {
    private fun frames(vararg data: String) = data.joinToString("") { "data:$it\n\n" }.reader().buffered()

    @Test fun `chat requires finish reason and terminal marker`() = runBlocking {
        val events = mutableListOf<LlmResponseChunk>()
        parseChatStream(frames(
            """{"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":17,"completion_tokens":2}}""",
            "[DONE]",
        )) { events.add(it) }
        assertEquals(listOf(LlmResponseChunk.TextDelta("hi"), LlmResponseChunk.Done("stop", 17, 2)), events)
    }

    @Test fun `chat truncation never emits completion`() = runBlocking {
        for (tail in listOf("", "data:[DONE]\n\n", "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n")) {
            val events = mutableListOf<LlmResponseChunk>()
            val result = runCatching {
                parseChatStream(StringReader("data:{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n$tail").buffered()) { events.add(it) }
            }
            assertTrue(result.exceptionOrNull() is StreamProtocolException || result.exceptionOrNull() is StreamInterruptedException)
            assertFalse(events.any { it is LlmResponseChunk.Done })
        }
    }

    @Test fun `SSE joins multiline data and ignores comments`() = runBlocking {
        val events = mutableListOf<LlmResponseChunk>()
        parseChatStream(StringReader(": ping\nevent: message\ndata: {\"choices\":\ndata: [{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n").buffered()) { events.add(it) }
        assertEquals(listOf(LlmResponseChunk.Done()), events)
    }

    @Test fun `malformed JSON fails rather than silently dropping arguments`() = runBlocking {
        assertTrue(runCatching { parseChatStream(frames("{oops", "[DONE]")) {} }.isFailure)
    }

    @Test fun `Claude requires message stop and tracks block index`() = runBlocking {
        val events = mutableListOf<LlmResponseChunk>()
        parseClaudeStream(frames(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t","name":"read"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}""",
            """{"type":"message_stop"}""",
        )) { events.add(it) }
        assertEquals(LlmResponseChunk.Done("tool_use", 10, 12), events.last())
        assertEquals(1, events.count { it is LlmResponseChunk.ToolCallEnd })
    }

    @Test fun `responses EOF never manufactures tool completion`() = runBlocking {
        val events = mutableListOf<LlmResponseChunk>()
        val result = runCatching {
            parseResponsesStream(frames(
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"i","call_id":"c","name":"write"}}""",
                """{"type":"response.function_call_arguments.delta","item_id":"i","delta":"{}"}""",
            )) { events.add(it) }
        }
        assertTrue(result.exceptionOrNull() is StreamProtocolException || result.exceptionOrNull() is StreamInterruptedException)
        assertFalse(events.any { it is LlmResponseChunk.ToolCallEnd || it is LlmResponseChunk.Done })
    }

    @Test fun `responses validates final arguments and status`() = runBlocking {
        for (status in listOf("completed", "incomplete")) {
            val events = mutableListOf<LlmResponseChunk>()
            val result = runCatching {
                parseResponsesStream(frames(
                    """{"type":"response.output_item.added","item":{"type":"function_call","id":"i","call_id":"c","name":"write"}}""",
                    """{"type":"response.function_call_arguments.delta","item_id":"i","delta":"{}"}""",
                    """{"type":"response.function_call_arguments.done","item_id":"i","arguments":"{}"}""",
                    """{"type":"response.completed","response":{"status":"$status","usage":{"input_tokens":3,"output_tokens":4}}}""",
                )) { events.add(it) }
            }
            assertEquals(status == "completed", result.isSuccess)
            assertEquals(status == "completed", events.any { it is LlmResponseChunk.Done })
        }
    }

    @Test fun `retry after supports seconds and dates`() {
        assertEquals(2000L, retryAfterMillis("2", 0))
        assertEquals(1000L, retryAfterMillis("Thu, 1 Jan 1970 00:00:01 GMT", 0))
        assertNull(retryAfterMillis("nonsense"))
    }
}
