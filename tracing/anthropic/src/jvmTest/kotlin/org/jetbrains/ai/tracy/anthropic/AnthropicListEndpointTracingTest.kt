/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
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
 * Tests for [org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicListEndpointHandler].
 *
 * Covers the dispatcher routing for `models` and `batches` list/retrieve operations.
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Anthropic API access required.
 */
@Tag("anthropic")
class AnthropicListEndpointTracingTest : BaseAITracingTest() {

    @Test
    fun `models list happy path traces operation name and pagination`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": [
                            {
                              "type": "model",
                              "id": "claude-haiku-4-5",
                              "display_name": "Claude 3.5 Haiku",
                              "created_at": "2024-10-01T00:00:00Z"
                            },
                            {
                              "type": "model",
                              "id": "claude-3-5-sonnet-latest",
                              "display_name": "Claude 3.5 Sonnet",
                              "created_at": "2024-10-01T00:00:00Z"
                            }
                          ],
                          "has_more": false,
                          "first_id": "claude-haiku-4-5",
                          "last_id": "claude-3-5-sonnet-latest"
                        }
                        """.trimIndent()
                    )
            )

            client.models().list()

            val trace = analyzeSpans().first()
            assertEquals("models.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("gen_ai.response.list.has_more")])
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.list.first_id")],
            )
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
        }
    }

    @Test
    fun `batches retrieve with bogus id traces operation name and 404`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type": "error",
                          "error": { "type": "not_found_error", "message": "Batch not found" }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.messages().batches().retrieve("msgbatch_does_not_exist")
            } catch (_: Exception) {
                // expected: 404
            }

            val trace = analyzeSpans().first()
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(404L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
            assertEquals("not_found_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    @Test
    fun `models retrieve traces request model id and parses response`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(30))
                .build()
                .apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type": "model",
                          "id": "claude-haiku-4-5",
                          "display_name": "Claude 3.5 Haiku",
                          "created_at": "2024-10-01T00:00:00Z",
                          "max_input_tokens": 200000,
                          "max_output_tokens": 8192,
                          "capabilities": { "batch": true, "vision": true }
                        }
                        """.trimIndent()
                    )
            )

            client.models().retrieve("claude-haiku-4-5")

            val trace = analyzeSpans().first()
            assertEquals("models.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")],
            )
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model.id")],
            )
            assertEquals(
                "Claude 3.5 Haiku",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")],
            )
            assertEquals(
                200000L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")],
            )
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-anthropic-key"
    }
}
