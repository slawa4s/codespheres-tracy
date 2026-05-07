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
