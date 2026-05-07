/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCountTokensParams
import com.anthropic.models.messages.Model
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicCountTokensHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Anthropic API access required.
 *
 * Extends [BaseAITracingTest] directly rather than [BaseAnthropicTracingTest] to avoid the
 * eager `ANTHROPIC_API_KEY` env-var requirement of the latter, while still being skippable
 * via `-Dskip.llm.providers=anthropic` thanks to the class-level `@Tag("anthropic")`.
 */
@Tag("anthropic")
class AnthropicCountTokensTracingTest : BaseAITracingTest() {

    @Test
    fun `count_tokens happy path traces input tokens, model, and request id from header`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            val requestId = "req_abc123"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("x-request-id", requestId)
                    .setBody("""{ "input_tokens": 42 }""")
            )

            val params = MessageCountTokensParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .addUserMessage("Hi!")
                .build()
            client.messages().countTokens(params)

            val trace = analyzeSpans().first()
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals(
                Model.CLAUDE_HAIKU_4_5.asString(),
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")],
            )
            assertEquals(42L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(requestId, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")])
        }
    }

    @Test
    fun `count_tokens 4xx error preserves operation name and status code`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type": "error",
                          "error": { "type": "invalid_request_error", "message": "Invalid model" }
                        }
                        """.trimIndent()
                    )
            )

            val params = MessageCountTokensParams.builder()
                .model("[non-existent model!]")
                .addUserMessage("Hi!")
                .build()
            try {
                client.messages().countTokens(params)
            } catch (_: Exception) {
                // expected: 400
            }

            val trace = analyzeSpans().first()
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(400L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-anthropic-key"
    }
}
