/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [BatchesOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] so no real OpenAI API key or network access
 * is required.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /v1/batches ============

    @Test
    fun `test CREATE batch - core request fields are traced with tracy namespace`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(MOCK_BATCH_RESPONSE)

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

            // Intercept the request and return the mock response, injecting output_expires_after
            // into the request body by using the SDK's additionalBodyProperties mechanism.
            server.enqueue(MOCK_BATCH_RESPONSE)

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

            server.enqueue(MOCK_BATCH_RESPONSE)

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
            // Both keys must appear (order-independent)
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

            server.enqueue(MOCK_BATCH_RESPONSE)

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

    @Test
    fun `test CREATE batch - response id and status are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(MOCK_BATCH_RESPONSE)

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
    fun `test RETRIEVE batch - operation name is batches_retrieve`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(MOCK_BATCH_RESPONSE)

            client.batches().retrieve(
                BatchRetrieveParams.builder().batchId("batch_abc123").build()
            )

            val trace = analyzeSpans().first()

            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ LIST: GET /v1/batches ============

    @Test
    fun `test LIST batches - operation name is batches_list`() = runTest {
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

    // ============ NETWORK ATTRIBUTES ============

    @Test
    fun `test network attributes are set for CREATE batch`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(MOCK_BATCH_RESPONSE)

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

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"

        private val MOCK_BATCH_RESPONSE = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "batch_abc123",
                  "object": "batch",
                  "endpoint": "/v1/chat/completions",
                  "errors": null,
                  "input_file_id": "file-abc123",
                  "completion_window": "24h",
                  "status": "validating",
                  "output_file_id": null,
                  "error_file_id": null,
                  "created_at": 1711471533,
                  "in_progress_at": null,
                  "expires_at": null,
                  "finalizing_at": null,
                  "completed_at": null,
                  "failed_at": null,
                  "expired_at": null,
                  "cancelling_at": null,
                  "cancelled_at": null,
                  "request_counts": {"total": 0, "completed": 0, "failed": 0},
                  "metadata": null
                }
                """.trimIndent()
            )
    }
}
