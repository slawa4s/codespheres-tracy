/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [AnthropicListEndpointHandler.handleResponseAttributes] error-type extraction.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] so no real Anthropic API key is required.
 */
class AnthropicListEndpointHandlerTest : BaseAITracingTest() {

    /**
     * Tests the standard nested Anthropic error shape:
     * ```json
     * { "type": "error", "error": { "type": "invalid_request_error", "message": "…" } }
     * ```
     * `error.type` should be extracted from `body["error"]["type"]`.
     */
    @Test
    fun `handleResponseAttributes sets error type from nested error object on 400`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(10))
                .build()
            instrument(client)

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "type": "error",
                            "error": {
                                "type": "invalid_request_error",
                                "message": "invalid batch request"
                            }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.messages().batches().create(minimalBatchParams())
            } catch (_: Exception) { /* expected: mock returns 400 */ }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace, "Expected a span to be created for the batch create call")
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(
                400L,
                trace.attributes[AttributeKey.longKey("http.response.status_code")],
                "http.response.status_code must be recorded even when the error branch returns early"
            )
            assertEquals(
                "invalid_request_error",
                trace.attributes[AttributeKey.stringKey("error.type")],
                "error.type should be extracted from the nested error object"
            )
        }
    }

    /**
     * Tests the flat Anthropic batch error shape where no nested `error` object is present:
     * ```json
     * { "type": "error", "message": "…" }
     * ```
     * `error.type` should fall back to the top-level `body["type"]` value.
     */
    @Test
    fun `handleResponseAttributes falls back to top-level type field for flat error envelope on 400`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(10))
                .build()
            instrument(client)

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "type": "error",
                            "message": "invalid batch request"
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.messages().batches().create(minimalBatchParams())
            } catch (_: Exception) { /* expected: mock returns 400 */ }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace, "Expected a span to be created for the batch create call")
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(
                400L,
                trace.attributes[AttributeKey.longKey("http.response.status_code")],
                "http.response.status_code must be recorded even when the error branch returns early"
            )
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("error.type")],
                "error.type should be non-null even when error is a flat envelope"
            )
            assertEquals(
                "error",
                trace.attributes[AttributeKey.stringKey("error.type")],
                "error.type should fall back to the top-level 'type' field"
            )
        }
    }

    /**
     * Verifies that `gen_ai.request.batch.size` is recorded for a batch create request containing
     * a single request entry. This covers the core attribute-recording path in
     * [AnthropicListEndpointHandler.handleRequestAttributes].
     */
    @Test
    fun `handleRequestAttributes records gen_ai_request_batch_size for single-request batch`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(10))
                .build()
            instrument(client)

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""
                    )
            )

            try {
                client.messages().batches().create(minimalBatchParams())
            } catch (_: Exception) { /* expected: mock returns 400 */ }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace, "Expected a span for the batch create call")
            assertEquals(
                1L,
                trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")],
                "gen_ai.request.batch.size should equal the number of requests in the batch"
            )
        }
    }

    /**
     * Verifies that the error-type extraction path in [AnthropicListEndpointHandler.handleResponseAttributes]
     * handles an unexpected JSON structure (JSON array instead of object) without throwing, and still
     * records the HTTP status code. This exercises the defensive try-catch around JSON body access.
     */
    @Test
    fun `handleResponseAttributes handles unexpected JSON array error body without throwing`() = runTest {
        withMockServer { server ->
            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .timeout(Duration.ofSeconds(10))
                .build()
            instrument(client)

            // Return a 400 with a JSON *array* body — structurally unexpected for Anthropic error envelopes.
            // The handler must not throw and must still record http.response.status_code.
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""[{"type":"error","message":"unexpected array"}]""")
            )

            try {
                client.messages().batches().create(minimalBatchParams())
            } catch (_: Exception) { /* expected: mock returns 400 */ }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace, "Expected a span even when response body is a JSON array")
            assertEquals(
                400L,
                trace.attributes[AttributeKey.longKey("http.response.status_code")],
                "http.response.status_code must be recorded regardless of body shape"
            )
        }
    }

    private fun minimalBatchParams(): BatchCreateParams =
        BatchCreateParams.builder()
            .addRequest(
                BatchCreateParams.Request.builder()
                    .customId("test-req-1")
                    .params(
                        BatchCreateParams.Request.Params.builder()
                            .model("[non-existent model]")
                            .addUserMessage("Say hi!")
                            .maxTokens(1)
                            .build()
                    )
                    .build()
            )
            .build()

    companion object {
        private const val MOCK_API_KEY = "test-api-key"
    }
}
