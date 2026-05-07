/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import com.openai.core.JsonValue
import com.openai.models.batches.BatchCancelParams
import com.openai.models.batches.BatchCreateParams
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
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key,
 * so they do not require access to the real OpenAI Batches API.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /v1/batches ============

    @Test
    fun `test CREATE batch sets correct operation name and api type`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(batchResponse())

            try {
                client.batches().create(createParams())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE batch extracts request body attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(batchResponse())

            val inputFileId = "file-abc123"
            val endpoint = BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS
            val completionWindow = BatchCreateParams.CompletionWindow._24H

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId(inputFileId)
                        .endpoint(endpoint)
                        .completionWindow(completionWindow)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                endpoint.asString(),
                trace.attributes[AttributeKey.stringKey("tracy.request.batch.endpoint")]
            )
            assertEquals(
                completionWindow.asString(),
                trace.attributes[AttributeKey.stringKey("tracy.request.batch.completion_window")]
            )
            assertEquals(
                inputFileId,
                trace.attributes[AttributeKey.stringKey("tracy.request.batch.input_file.id")]
            )
        }
    }

    @Test
    fun `test CREATE batch extracts metadata keys`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(batchResponse())

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId("file-abc123")
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .metadata(
                            BatchCreateParams.Metadata.builder()
                                .putAdditionalProperty("customer_id", JsonValue.from("user_123"))
                                .putAdditionalProperty("batch_description", JsonValue.from("My batch"))
                                .build()
                        )
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            val metadataKeys = trace.attributes[AttributeKey.stringKey("tracy.request.metadata.keys")]
            assertNotNull(metadataKeys, "Metadata keys should be present")
            // Keys may be in any order; verify both are present
            val keySet = metadataKeys!!.split(",").toSet()
            assert(keySet.contains("customer_id")) { "customer_id should be in metadata keys" }
            assert(keySet.contains("batch_description")) { "batch_description should be in metadata keys" }
        }
    }

    @Test
    fun `test CREATE batch extracts output_expires_after attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            server.enqueue(batchResponse())

            val expiresSeconds = 86400L

            try {
                client.batches().create(
                    BatchCreateParams.builder()
                        .inputFileId("file-abc123")
                        .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                        .completionWindow(BatchCreateParams.CompletionWindow._24H)
                        .outputExpiresAfter(
                            BatchCreateParams.OutputExpiresAfter.builder()
                                .anchor(JsonValue.from("req_created_at"))
                                .seconds(expiresSeconds)
                                .build()
                        )
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "req_created_at",
                trace.attributes[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")]
            )
            assertEquals(
                expiresSeconds,
                trace.attributes[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")]
            )
        }
    }

    @Test
    fun `test CREATE batch extracts response attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val batchId = "batch_create_123"
            val status = "validating"
            val createdAt = Instant.now().epochSecond
            val total = 10L
            val completed = 0L
            val failed = 0L

            server.enqueue(
                batchResponse(
                    id = batchId,
                    status = status,
                    createdAt = createdAt,
                    requestCountsTotal = total,
                    requestCountsCompleted = completed,
                    requestCountsFailed = failed,
                )
            )

            try {
                client.batches().create(createParams())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals(status, trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.batch.created_at")])
            assertEquals(total, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(completed, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(failed, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    // ============ RETRIEVE: GET /v1/batches/{id} ============

    @Test
    fun `test RETRIEVE batch sets correct operation name`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val batchId = "batch_retrieve_456"
            server.enqueue(batchResponse(id = batchId))

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

            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
        }
    }

    // ============ CANCEL: POST /v1/batches/{id}/cancel ============

    @Test
    fun `test CANCEL batch sets correct operation name`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val batchId = "batch_cancel_789"
            server.enqueue(batchResponse(id = batchId, status = "cancelling"))

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

            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("cancelling", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
        }
    }

    // ============ LIFECYCLE: create → retrieve → cancel ============

    @Test
    fun `test batch lifecycle - create then retrieve then cancel`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1),
            ).apply { instrument(this) }

            val batchId = "batch_lifecycle_001"
            server.enqueue(batchResponse(id = batchId, status = "validating"))
            server.enqueue(batchResponse(id = batchId, status = "in_progress"))
            server.enqueue(batchResponse(id = batchId, status = "cancelling"))

            try { client.batches().create(createParams()) } catch (_: Exception) {}
            try {
                client.batches().retrieve(BatchRetrieveParams.builder().batchId(batchId).build())
            } catch (_: Exception) {}
            try {
                client.batches().cancel(BatchCancelParams.builder().batchId(batchId).build())
            } catch (_: Exception) {}

            val traces = analyzeSpans()
            assertTracesCount(3, traces)

            assertEquals("batches.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches.retrieve", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches.cancel", traces[2].attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            assertEquals(batchId, traces[0].attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals(batchId, traces[1].attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals(batchId, traces[2].attributes[AttributeKey.stringKey("tracy.batch.id")])
        }
    }

    // ============ HELPER METHODS ============

    private fun createParams(): BatchCreateParams {
        return BatchCreateParams.builder()
            .inputFileId("file-abc123")
            .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
            .completionWindow(BatchCreateParams.CompletionWindow._24H)
            .build()
    }

    private fun batchResponse(
        id: String = "batch_default_001",
        status: String = "validating",
        createdAt: Long = Instant.now().epochSecond,
        requestCountsTotal: Long = 0L,
        requestCountsCompleted: Long = 0L,
        requestCountsFailed: Long = 0L,
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "batch",
                  "endpoint": "/v1/chat/completions",
                  "input_file_id": "file-abc123",
                  "completion_window": "24h",
                  "status": "$status",
                  "created_at": $createdAt,
                  "request_counts": {
                    "total": $requestCountsTotal,
                    "completed": $requestCountsCompleted,
                    "failed": $requestCountsFailed
                  }
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
