/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.models.messages.Model
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for Anthropic Message Batches API tracing.
 *
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock Anthropic API key,
 * so they do not require access to the real Anthropic Batches API.
 *
 * See: [Anthropic Message Batches API](https://docs.anthropic.com/en/api/creating-message-batches)
 */
@Tag("anthropic")
class AnthropicBatchTracingTest : BaseAnthropicTracingTest() {

    @Test
    fun `test batch creation lifecycle is traced`() = runTest {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            val batchId = "msgbatch_test123"
            val processingCount = 3L
            val succeededCount = 1L
            val erroredCount = 0L
            val canceledCount = 0L
            val expiredCount = 0L

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "$batchId",
                            "type": "message_batch",
                            "processing_status": "in_progress",
                            "request_counts": {
                                "processing": $processingCount,
                                "succeeded": $succeededCount,
                                "errored": $erroredCount,
                                "canceled": $canceledCount,
                                "expired": $expiredCount
                            },
                            "ended_at": null,
                            "created_at": "2024-09-24T18:37:24.100435Z",
                            "expires_at": "2024-09-25T18:37:24.100435Z",
                            "cancel_initiated_at": null,
                            "results_url": null
                        }
                        """.trimIndent()
                    )
            )

            val params = BatchCreateParams.builder()
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("request-1")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(100L)
                                .addUserMessage("Hello!")
                                .build()
                        )
                        .build()
                )
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("request-2")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(100L)
                                .addUserMessage("Hi!")
                                .build()
                        )
                        .build()
                )
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("request-3")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(100L)
                                .addUserMessage("Hey!")
                                .build()
                        )
                        .build()
                )
                .build()

            client.messages().batches().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.batch.processing_status")])
            assertEquals(processingCount, trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.processing")])
            assertEquals(succeededCount, trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.succeeded")])
            assertEquals(erroredCount, trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.errored")])
            assertEquals(canceledCount, trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.canceled")])
            assertEquals(expiredCount, trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.expired")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.batch.requests.count")])
        }
    }

    @Test
    fun `test batch creation with invalid empty requests response is handled gracefully`() = runTest {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            // Mock server returns an error response (e.g. requests list empty)
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
                                "message": "requests: must have at least 1 item"
                            }
                        }
                        """.trimIndent()
                    )
            )

            val params = BatchCreateParams.builder()
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("request-1")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(100L)
                                .addUserMessage("Hello!")
                                .build()
                        )
                        .build()
                )
                .build()

            try {
                client.messages().batches().create(params)
            } catch (_: Exception) {
                // expected: the server returns 400
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Span is recorded even for errors; batch-specific response attrs are absent since body is an error
            assertNotNull(trace)
            // The request attribute should still be set (1 request was in the batch body)
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.batch.requests.count")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
