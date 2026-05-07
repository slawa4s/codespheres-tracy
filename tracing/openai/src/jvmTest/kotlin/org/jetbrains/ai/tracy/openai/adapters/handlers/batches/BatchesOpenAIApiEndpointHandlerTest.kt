/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import com.openai.models.batches.BatchCreateParams
import com.openai.models.batches.BatchRetrieveParams
import com.openai.models.batches.BatchCancelParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [BatchesOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock OpenAI API key, so they do not require access
 * to the real OpenAI Batches API.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /v1/batches ============

    @Test
    fun `test create batch response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(
                id = "batch_abc123",
                status = "validating",
                createdAt = 1711471533,
                total = 100,
                completed = 0,
                failed = 0,
            )

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-abc123")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batch_abc123", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals("1711471533", trace.attributes[AttributeKey.stringKey("tracy.batch.created_at")])
            assertEquals(100L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `test create batch response also sets legacy gen_ai batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-abc123")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batch_abc123", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.status")])
        }
    }

    @Test
    fun `test create batch operation name is batches create`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-abc123")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ RETRIEVE: GET /v1/batches/{batch_id} ============

    @Test
    fun `test retrieve batch response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(
                id = "batch_abc123",
                status = "completed",
                createdAt = 1711471533,
                total = 50,
                completed = 45,
                failed = 5,
            )

            client.batches().retrieve(
                BatchRetrieveParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batch_abc123", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals("1711471533", trace.attributes[AttributeKey.stringKey("tracy.batch.created_at")])
            assertEquals(50L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(45L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(5L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `test retrieve batch operation name is batches retrieve`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "completed")

            client.batches().retrieve(
                BatchRetrieveParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ CANCEL: POST /v1/batches/{batch_id}/cancel ============

    @Test
    fun `test cancel batch response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(
                id = "batch_abc123",
                status = "cancelling",
                createdAt = 1711471533,
                total = 200,
                completed = 100,
                failed = 0,
            )

            client.batches().cancel(
                BatchCancelParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batch_abc123", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("cancelling", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals("1711471533", trace.attributes[AttributeKey.stringKey("tracy.batch.created_at")])
            assertEquals(200L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(100L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `test cancel batch operation name is batches cancel`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "cancelling")

            client.batches().cancel(
                BatchCancelParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ MISSING FIELDS ============

    @Test
    fun `test batch attributes are not set when absent from response`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            // Response without created_at or request_counts
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"batch_abc123","object":"batch","status":"validating","endpoint":"/v1/chat/completions","completion_window":"24h"}""")
            )

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-abc123")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            // id and status still set
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            // missing fields should not be set
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.batch.created_at")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueBatchResponse(
        id: String,
        status: String,
        createdAt: Long = 1711471533,
        total: Int = 100,
        completed: Int = 0,
        failed: Int = 0,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "$id",
                      "object": "batch",
                      "endpoint": "/v1/chat/completions",
                      "errors": null,
                      "input_file_id": "file-abc123",
                      "completion_window": "24h",
                      "status": "$status",
                      "output_file_id": null,
                      "error_file_id": null,
                      "created_at": $createdAt,
                      "in_progress_at": null,
                      "expires_at": null,
                      "finalizing_at": null,
                      "completed_at": null,
                      "failed_at": null,
                      "expired_at": null,
                      "cancelling_at": null,
                      "cancelled_at": null,
                      "request_counts": {
                        "total": $total,
                        "completed": $completed,
                        "failed": $failed
                      },
                      "metadata": null
                    }
                    """.trimIndent()
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
