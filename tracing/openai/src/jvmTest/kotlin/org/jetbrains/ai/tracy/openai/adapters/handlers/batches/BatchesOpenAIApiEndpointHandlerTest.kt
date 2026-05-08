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
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [BatchesOpenAIApiEndpointHandler].
 *
 * Uses [MockWebServer] so no real OpenAI API key or network access is required.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /v1/batches — request attributes ============

    @Test
    fun `test CREATE batch - core request fields use tracy namespace`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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
            assertEquals("file-abc123", trace.attributes[AttributeKey.stringKey("tracy.request.batch.input_file.id")])
            assertEquals("/v1/chat/completions", trace.attributes[AttributeKey.stringKey("tracy.request.batch.endpoint")])
            assertEquals("24h", trace.attributes[AttributeKey.stringKey("tracy.request.batch.completion_window")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE batch - output_expires_after fields are traced when present`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_exp123", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-exp123")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .putAdditionalBodyProperty(
                        "output_expires_after",
                        com.openai.core.JsonValue.from(
                            mapOf("anchor" to "req_start", "seconds" to 3600)
                        )
                    )
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("req_start", trace.attributes[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")])
            assertEquals(3600L, trace.attributes[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")])
        }
    }

    @Test
    fun `test CREATE batch - metadata keys are traced when metadata is present`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_meta456", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-meta456")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .metadata(
                        BatchCreateParams.Metadata.builder()
                            .putAdditionalProperty("project", com.openai.core.JsonValue.from("my-project"))
                            .putAdditionalProperty("env", com.openai.core.JsonValue.from("prod"))
                            .build()
                    )
                    .build()
            )

            val trace = analyzeSpans().first()
            val metadataKeys = trace.attributes[AttributeKey.stringKey("tracy.request.metadata.keys")]
            assertNotNull(metadataKeys, "tracy.request.metadata.keys should be set")
            val keySet = metadataKeys!!.split(",").toSet()
            assertEquals(setOf("project", "env"), keySet)
        }
    }

    @Test
    fun `test CREATE batch - output_expires_after and metadata absent when not provided`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_minimal", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-minimal")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")])
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.request.metadata.keys")])
        }
    }

    // ============ CREATE: POST /v1/batches — response attributes ============

    @Test
    fun `test CREATE batch - response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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
    fun `test CREATE batch - response also sets legacy gen_ai batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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

    // ============ RETRIEVE: GET /v1/batches/{batch_id} ============

    @Test
    fun `test RETRIEVE batch - response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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
    fun `test RETRIEVE batch - operation name is batches retrieve`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "completed")

            client.batches().retrieve(
                BatchRetrieveParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ CANCEL: POST /v1/batches/{batch_id}/cancel ============

    @Test
    fun `test CANCEL batch - response sets tracy batch attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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
    fun `test CANCEL batch - operation name is batches cancel`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_abc123", status = "cancelling")

            client.batches().cancel(
                BatchCancelParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ LIST: GET /v1/batches ============

    @Test
    fun `test LIST batches - operation name is batches list`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[],"has_more":false}""")
            )

            client.batches().list(BatchListParams.builder().build())

            val trace = analyzeSpans().first()
            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ ABSENT FIELDS ============

    @Test
    fun `test batch attributes are not set when absent from response`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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
            // id and status should be set
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            // missing fields should not be set
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.batch.created_at")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertNull(trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    // ============ NETWORK ATTRIBUTES ============

    @Test
    fun `test network attributes are set for CREATE batch`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueBatchResponse(id = "batch_net", status = "validating")

            client.batches().create(
                BatchCreateParams.builder()
                    .inputFileId("file-net-test")
                    .endpoint(BatchCreateParams.Endpoint.V1_CHAT_COMPLETIONS)
                    .completionWindow(BatchCreateParams.CompletionWindow._24H)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("openai", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")])
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
