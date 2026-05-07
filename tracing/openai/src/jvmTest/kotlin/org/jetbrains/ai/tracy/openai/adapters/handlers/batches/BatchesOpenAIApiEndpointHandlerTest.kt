/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import com.openai.models.batches.BatchCancelParams
import com.openai.models.batches.BatchCreateParams
import com.openai.models.batches.BatchListParams
import com.openai.models.batches.BatchRetrieveParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they do not
 * require access to the real OpenAI Batches API or any specific account configuration.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /batches ============

    @Test
    fun `test CREATE batch endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "batch-create-abc123"
            server.enqueue(enqueueBatchResponse(id = batchId, status = "validating"))

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId("file-input-xyz")
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "batches.create", NOT "batch" from the response `object` field
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE batch endpoint traces request and response attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "batch-create-meta456"
            val inputFileId = "file-input-abc"
            val endpoint = "/v1/chat/completions"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$batchId",
                          "object": "batch",
                          "endpoint": "$endpoint",
                          "input_file_id": "$inputFileId",
                          "completion_window": "24h",
                          "status": "validating",
                          "created_at": ${Instant.now().epochSecond},
                          "request_counts": {
                            "total": 10,
                            "completed": 0,
                            "failed": 0
                          }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId(inputFileId)
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals(inputFileId, trace.attributes[AttributeKey.stringKey("tracy.batch.input_file_id")])
            assertEquals(endpoint, trace.attributes[AttributeKey.stringKey("tracy.batch.endpoint")])
            assertEquals("24h", trace.attributes[AttributeKey.stringKey("tracy.batch.completion_window")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.batch.created_at")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `test CREATE batch endpoint traces request body fields`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val inputFileId = "file-request-trace-789"
            server.enqueue(enqueueBatchResponse(id = "batch-req-trace", status = "validating"))

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId(inputFileId)
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(inputFileId, trace.attributes[AttributeKey.stringKey("tracy.request.input_file_id")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.request.endpoint")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.request.completion_window")])
        }
    }

    // ============ RETRIEVE: GET /batches/{batch_id} ============

    @Test
    fun `test RETRIEVE batch endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "batch-retrieve-xyz789"
            server.enqueue(enqueueBatchResponse(id = batchId, status = "in_progress"))

            try {
                client.batches().retrieve(
                    BatchRetrieveParams.builder()
                        .batchId(batchId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "batches.retrieve", NOT "batch" from the response `object` field
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
        }
    }

    // ============ CANCEL: POST /batches/{batch_id}/cancel ============

    @Test
    fun `test CANCEL batch endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "batch-cancel-def456"
            server.enqueue(enqueueBatchResponse(id = batchId, status = "cancelling"))

            try {
                client.batches().cancel(
                    BatchCancelParams.builder()
                        .batchId(batchId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "batches.cancel", NOT "batch" from the response `object` field
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("cancelling", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
        }
    }

    // ============ LIST: GET /batches ============

    @Test
    fun `test LIST batches endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueBatchListResponse(count = 3))

            try {
                client.batches().list(BatchListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "batches.list", NOT "list" from the response `object` field
            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.batch.count")])
        }
    }

    // ============ Lifecycle tests ============

    @Test
    fun `test batch lifecycle - create then retrieve then cancel`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "batch-lifecycle-001"

            server.enqueue(enqueueBatchResponse(id = batchId, status = "validating"))
            server.enqueue(enqueueBatchResponse(id = batchId, status = "in_progress"))
            server.enqueue(enqueueBatchResponse(id = batchId, status = "cancelling"))

            try { client.batches().create(BatchCreateParams.builder().inputFileId("file-input").endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS).completionWindow(BatchCreateParams.CompletionWindow._24H).build()) } catch (_: Exception) {}
            try { client.batches().retrieve(BatchRetrieveParams.builder().batchId(batchId).build()) } catch (_: Exception) {}
            try { client.batches().cancel(BatchCancelParams.builder().batchId(batchId).build()) } catch (_: Exception) {}

            val traces = analyzeSpans()
            assertTracesCount(3, traces)

            assertEquals("batches.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches.retrieve", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches.cancel", traces[2].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueBatchResponse(
        id: String,
        status: String,
        inputFileId: String = "file-input-default",
        endpoint: String = "/v1/chat/completions",
        completionWindow: String = "24h",
        createdAt: Long = Instant.now().epochSecond
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "batch",
                  "endpoint": "$endpoint",
                  "input_file_id": "$inputFileId",
                  "completion_window": "$completionWindow",
                  "status": "$status",
                  "created_at": $createdAt,
                  "request_counts": {
                    "total": 0,
                    "completed": 0,
                    "failed": 0
                  }
                }
                """.trimIndent()
            )
    }

    private fun enqueueBatchListResponse(
        count: Int,
        hasMore: Boolean = false
    ): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """
            {
              "id": "batch-list-$i",
              "object": "batch",
              "endpoint": "/v1/chat/completions",
              "input_file_id": "file-input-$i",
              "completion_window": "24h",
              "status": "completed",
              "created_at": ${Instant.now().epochSecond},
              "request_counts": {
                "total": 5,
                "completed": 5,
                "failed": 0
              }
            }
            """.trimIndent()
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "object": "list",
                  "data": [$items],
                  "first_id": "batch-list-1",
                  "last_id": "batch-list-$count",
                  "has_more": $hasMore
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
