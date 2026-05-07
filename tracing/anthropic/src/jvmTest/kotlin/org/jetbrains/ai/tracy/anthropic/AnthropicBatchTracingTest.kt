/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [AnthropicLLMTracingAdapter] batch endpoint routing.
 *
 * Uses [MockWebServer] with raw OkHttp calls so no real Anthropic API key is required.
 * Verifies that URLs containing `batches` in the path are routed to
 * [org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicBatchesEndpointHandler]
 * and the correct `gen_ai.response.batch.*` / `anthropic.api.type` attributes are emitted.
 */
class AnthropicBatchTracingTest : BaseAITracingTest() {

    // ============ POST /v1/messages/batches ============

    @Test
    fun `test batch create request sets anthropic api type attribute`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(batchResponse(processingStatus = "in_progress", processing = 2))

            postBatch(client, server, BATCH_CREATE_PATH, batchRequestBody(count = 2))

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    @Test
    fun `test batch create request sets batch size attribute`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(batchResponse(processingStatus = "in_progress", processing = 3))

            postBatch(client, server, BATCH_CREATE_PATH, batchRequestBody(count = 3))

            val trace = analyzeSpans().first()
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
        }
    }

    @Test
    fun `test batch create response attributes are extracted correctly`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(batchResponse(
                id = "msgbatch_abc123",
                processingStatus = "in_progress",
                processing = 5,
                succeeded = 0,
                errored = 0,
                canceled = 0,
                expired = 0,
            ))

            postBatch(client, server, BATCH_CREATE_PATH, batchRequestBody(count = 5))

            val trace = analyzeSpans().first()
            assertEquals("msgbatch_abc123", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(5L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test batch create with empty requests still sets anthropic api type`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(batchResponse(processingStatus = "in_progress", processing = 0))

            postBatch(client, server, BATCH_CREATE_PATH, """{"requests": []}""")

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
        }
    }

    @Test
    fun `test batch create with ended status has all request counts set`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(batchResponse(
                id = "msgbatch_ended",
                processingStatus = "ended",
                processing = 0,
                succeeded = 3,
                errored = 1,
                canceled = 0,
                expired = 0,
            ))

            postBatch(client, server, BATCH_CREATE_PATH, batchRequestBody(count = 4))

            val trace = analyzeSpans().first()
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
        }
    }

    // ============ GET /v1/messages/batches/{batch_id} ============

    @Test
    fun `test batch retrieve sets batch response attributes`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)
            val batchId = "msgbatch_retrieve_test"

            server.enqueue(batchResponse(
                id = batchId,
                processingStatus = "ended",
                processing = 0,
                succeeded = 2,
            ))

            getBatch(client, server, "/v1/messages/batches/$batchId")

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ============ POST /v1/messages/batches/{batch_id}/cancel ============

    @Test
    fun `test batch cancel sets anthropic api type and response attributes`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)
            val batchId = "msgbatch_cancel_test"

            server.enqueue(batchResponse(
                id = batchId,
                processingStatus = "canceling",
            ))

            postBatch(client, server, "/v1/messages/batches/$batchId/cancel", "")

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("canceling", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ============ Error handling ============

    @Test
    fun `test batch error response is traced with error status`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                        {
                            "type": "error",
                            "error": {
                                "type": "invalid_request_error",
                                "message": "requests array cannot be empty"
                            }
                        }
                    """.trimIndent())
            )

            postBatch(client, server, BATCH_CREATE_PATH, """{"requests": []}""")

            val trace = analyzeSpans().first()
            // batch API type should still be set even for error responses
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
        }
    }

    // ============ Non-batch endpoints unaffected ============

    @Test
    fun `test messages endpoint is not treated as a batch endpoint`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient(server)

            // A regular messages response (not a batch response)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                        {
                            "id": "msg_123",
                            "type": "message",
                            "role": "assistant",
                            "content": [{"type": "text", "text": "Hello!"}],
                            "model": "claude-3-5-haiku-20241022",
                            "stop_reason": "end_turn",
                            "usage": {"input_tokens": 10, "output_tokens": 5}
                        }
                    """.trimIndent())
            )

            postBatch(client, server, "/v1/messages", """
                {
                    "model": "claude-3-5-haiku-20241022",
                    "max_tokens": 1024,
                    "messages": [{"role": "user", "content": "Hello!"}]
                }
            """.trimIndent())

            val trace = analyzeSpans().first()
            // anthropic.api.type should NOT be set for the messages endpoint
            val apiType = trace.attributes[AttributeKey.stringKey("anthropic.api.type")]
            assertEquals(null, apiType, "Messages endpoint should not have anthropic.api.type set")
            // Regular Messages API attributes should be present
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
        }
    }

    // ============ Helpers ============

    private fun instrumentedClient(server: MockWebServer): OkHttpClient {
        return instrument(OkHttpClient(), AnthropicLLMTracingAdapter())
    }

    private fun postBatch(client: OkHttpClient, server: MockWebServer, path: String, body: String) {
        val mediaType = "application/json".toMediaTypeOrNull()
        val requestBody = body.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(server.url(path))
            .post(requestBody)
            .build()
        client.newCall(request).execute().use { }
    }

    private fun getBatch(client: OkHttpClient, server: MockWebServer, path: String) {
        val request = Request.Builder()
            .url(server.url(path))
            .get()
            .build()
        client.newCall(request).execute().use { }
    }

    private fun batchRequestBody(count: Int): String {
        val requests = (1..count).joinToString(",\n") { i ->
            """
            {
                "custom_id": "request-$i",
                "params": {
                    "model": "claude-3-5-haiku-20241022",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hello $i"}]
                }
            }
            """.trimIndent()
        }
        return """{"requests": [$requests]}"""
    }

    private fun batchResponse(
        id: String = "msgbatch_test123",
        processingStatus: String = "in_progress",
        processing: Int = 0,
        succeeded: Int = 0,
        errored: Int = 0,
        canceled: Int = 0,
        expired: Int = 0,
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "$id",
                    "type": "message_batch",
                    "processing_status": "$processingStatus",
                    "request_counts": {
                        "processing": $processing,
                        "succeeded": $succeeded,
                        "errored": $errored,
                        "canceled": $canceled,
                        "expired": $expired
                    },
                    "ended_at": null,
                    "created_at": "2024-09-24T18:37:24.100435Z",
                    "expires_at": "2024-09-25T18:37:24.100435Z",
                    "cancel_initiated_at": null,
                    "results_url": null
                }
            """.trimIndent())
    }

    companion object {
        private const val BATCH_CREATE_PATH = "/v1/messages/batches"
    }
}
