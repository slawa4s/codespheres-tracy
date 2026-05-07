/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters

import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests that [OpenAILLMTracingAdapter] sets the required span attributes
 * (`gen_ai.provider.name`, `server.address`, `server.port`) on every OpenAI span.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] so no real API key is needed.
 */
@Tag("openai")
class OpenAILLMTracingAdapterTest : BaseOpenAITracingTest() {

    @Test
    fun `test gen_ai provider name is set to openai`() = runTest {
        withMockServer { server ->
            server.enqueue(chatCompletionResponse())

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            client.chat().completions().create(minimalChatParams())

            val trace = analyzeSpans().first()
            assertEquals("openai", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
        }
    }

    @Test
    fun `test server address is set to host from request URL`() = runTest {
        withMockServer { server ->
            server.enqueue(chatCompletionResponse())

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            client.chat().completions().create(minimalChatParams())

            val trace = analyzeSpans().first()
            val serverAddress = trace.attributes[AttributeKey.stringKey("server.address")]
            assertNotNull(serverAddress, "server.address must be present")
            assert(serverAddress!!.isNotEmpty()) { "server.address must not be empty" }
            // MockWebServer binds to localhost
            assertEquals("localhost", serverAddress)
        }
    }

    @Test
    fun `test server port is set for http scheme`() = runTest {
        withMockServer { server ->
            server.enqueue(chatCompletionResponse())

            val client = createOpenAIClient(
                url = server.url("/").toString(),  // MockWebServer uses http://
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            client.chat().completions().create(minimalChatParams())

            val trace = analyzeSpans().first()
            val serverPort = trace.attributes[AttributeKey.longKey("server.port")]
            assertNotNull(serverPort, "server.port must be present")
            // http scheme → port 80
            assertEquals(80L, serverPort)
        }
    }

    @Test
    fun `test all three required attributes are set together`() = runTest {
        withMockServer { server ->
            server.enqueue(chatCompletionResponse())

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            client.chat().completions().create(minimalChatParams())

            val trace = analyzeSpans().first()
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")], "gen_ai.provider.name must be present")
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")], "server.address must be present")
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")], "server.port must be present")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun minimalChatParams(): ChatCompletionCreateParams =
        ChatCompletionCreateParams.builder()
            .addUserMessage("Hello")
            .model(ChatModel.GPT_4O_MINI)
            .build()

    private fun chatCompletionResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "id": "chatcmpl-test123",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "gpt-4o-mini",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Hi there!"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 3,
                "total_tokens": 8
              }
            }
            """.trimIndent()
        )

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
