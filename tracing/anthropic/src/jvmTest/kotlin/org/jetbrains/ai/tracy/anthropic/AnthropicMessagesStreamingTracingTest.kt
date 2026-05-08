/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Tests the streaming branch of [org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesHandler]
 * (`message_start` and `message_delta` SSE events).
 *
 * The pre-refactor `AnthropicTracingTest` covers non-streaming completions; this suite focuses
 * specifically on the SSE accumulation logic introduced when the messages handler was extracted.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Anthropic API access required.
 */
@Tag("anthropic")
class AnthropicMessagesStreamingTracingTest : BaseAITracingTest() {

    @Test
    fun `streaming response carries id, model and usage tokens from SSE events`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            val sseBody = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_abc","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )

            val params = MessageCreateParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .maxTokens(64L)
                .addUserMessage("Say hi")
                .build()
            client.messages().createStreaming(params).use { stream -> stream.stream().count() }

            val trace = analyzeSpans().first()
            assertEquals("msg_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model")],
            )
            assertEquals("assistant", trace.attributes[AttributeKey.stringKey("gen_ai.response.role")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(5L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-anthropic-key"
    }
}
