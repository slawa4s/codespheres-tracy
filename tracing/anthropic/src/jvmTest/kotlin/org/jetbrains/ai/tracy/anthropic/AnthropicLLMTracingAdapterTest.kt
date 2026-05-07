/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests that [org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter] sets the
 * required span attributes (`gen_ai.provider.name`, `server.address`, `server.port`) on every
 * Anthropic span.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] so no real API key is needed.
 */
@Tag("anthropic")
class AnthropicLLMTracingAdapterTest : BaseAnthropicTracingTest() {

    @Test
    fun `test gen_ai provider name is set to anthropic`() = runTest {
        withMockServer { server ->
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            try {
                client.messages().create(minimalMessageParams())
            } catch (_: Exception) {
                // SDK may reject the mock response body — span attributes are still captured
            }

            val trace = analyzeSpans().first()
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
        }
    }

    @Test
    fun `test server address is set to host from request URL`() = runTest {
        withMockServer { server ->
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            try {
                client.messages().create(minimalMessageParams())
            } catch (_: Exception) {
                // SDK may reject the mock response body — span attributes are still captured
            }

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
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),  // MockWebServer uses http://
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            try {
                client.messages().create(minimalMessageParams())
            } catch (_: Exception) {
                // SDK may reject the mock response body — span attributes are still captured
            }

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
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            try {
                client.messages().create(minimalMessageParams())
            } catch (_: Exception) {
                // SDK may reject the mock response body — span attributes are still captured
            }

            val trace = analyzeSpans().first()
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")], "gen_ai.provider.name must be present")
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")], "server.address must be present")
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")], "server.port must be present")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun minimalMessageParams(): MessageCreateParams =
        MessageCreateParams.builder()
            .addUserMessage("Hello")
            .model(Model.CLAUDE_HAIKU_4_5)
            .maxTokens(10L)
            .build()

    private fun messagesResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "id": "msg_test123",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "Hi!"}],
              "model": "claude-haiku-4-5",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 5, "output_tokens": 2}
            }
            """.trimIndent()
        )

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
