/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.models.messages.Model
import com.anthropic.models.messages.batches.BatchCancelParams
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.BatchListParams
import com.anthropic.models.messages.batches.BatchRetrieveParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.anthropic.adapters.handlers.BatchesAnthropicApiEndpointHandler
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [org.jetbrains.ai.tracy.anthropic.adapters.handlers.BatchesAnthropicApiEndpointHandler].
 *
 * Uses [MockWebServer] with a mock API key and does not require access to the real
 * Anthropic Message Batches API.
 *
 * Test scenarios correspond to:
 * - `anthropic/batches/lifecycle` — create, retrieve, cancel, list batches
 */
@Tag("anthropic")
class AnthropicBatchesTracingTest : BaseAnthropicTracingTest() {

    // ============ UNIT TESTS: Operation Detection ============

    @Test
    fun `detectOperation - POST to batches returns batch_create`() {
        val handler = BatchesAnthropicApiEndpointHandler()
        val request = buildRequest(listOf("v1", "messages", "batches"), "POST")
        assertEquals(BatchesAnthropicApiEndpointHandler.BATCH_CREATE, handler.detectOperation(request))
    }

    @Test
    fun `detectOperation - GET to batches returns batch_list`() {
        val handler = BatchesAnthropicApiEndpointHandler()
        val request = buildRequest(listOf("v1", "messages", "batches"), "GET")
        assertEquals(BatchesAnthropicApiEndpointHandler.BATCH_LIST, handler.detectOperation(request))
    }

    @Test
    fun `detectOperation - GET with batch ID returns batch_retrieve`() {
        val handler = BatchesAnthropicApiEndpointHandler()
        val request = buildRequest(listOf("v1", "messages", "batches", "msgbatch_abc123"), "GET")
        assertEquals(BatchesAnthropicApiEndpointHandler.BATCH_RETRIEVE, handler.detectOperation(request))
    }

    @Test
    fun `detectOperation - POST to cancel returns batch_cancel`() {
        val handler = BatchesAnthropicApiEndpointHandler()
        val request = buildRequest(listOf("v1", "messages", "batches", "msgbatch_abc123", "cancel"), "POST")
        assertEquals(BatchesAnthropicApiEndpointHandler.BATCH_CANCEL, handler.detectOperation(request))
    }

    // ============ INTEGRATION: Batch Lifecycle ============

    @Test
    fun `test CREATE batch - operation and size are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "msgbatch_create_001"
            val createdAt = 1714858523L
            val expiresAt = 1715463323L

            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "in_progress",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 2,
                succeeded = 0,
                errored = 0,
                canceled = 0,
                expired = 0
            )

            val params = BatchCreateParams.builder()
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("req-1")
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
                        .customId("req-2")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(100L)
                                .addUserMessage("Hi!")
                                .build()
                        )
                        .build()
                )
                .build()

            client.messages().batches().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batch.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.created_at")])
            assertEquals(expiresAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.expires_at")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test RETRIEVE batch - operation and response attributes are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "msgbatch_retrieve_002"
            val createdAt = 1714858523L
            val expiresAt = 1715463323L

            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "ended",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 0,
                succeeded = 3,
                errored = 1,
                canceled = 0,
                expired = 0
            )

            val params = BatchRetrieveParams.builder()
                .messageBatchId(batchId)
                .build()
            client.messages().batches().retrieve(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batch.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.created_at")])
            assertEquals(expiresAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.expires_at")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test CANCEL batch - operation and response attributes are traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "msgbatch_cancel_003"
            val createdAt = 1714858523L
            val expiresAt = 1715463323L

            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "canceling",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 1,
                succeeded = 1,
                errored = 0,
                canceled = 2,
                expired = 0
            )

            val params = BatchCancelParams.builder()
                .messageBatchId(batchId)
                .build()
            client.messages().batches().cancel(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batch.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("canceling", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.created_at")])
            assertEquals(expiresAt, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.expires_at")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test LIST batches - operation is traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[],"has_more":false,"first_id":null,"last_id":null}"""))

            client.messages().batches().list(BatchListParams.none())

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batch.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test batch lifecycle - create then retrieve then cancel`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val batchId = "msgbatch_lifecycle_001"
            val createdAt = 1714858523L
            val expiresAt = 1715463323L

            // CREATE response
            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "in_progress",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 1,
                succeeded = 0,
                errored = 0,
                canceled = 0,
                expired = 0
            )
            // RETRIEVE response
            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "in_progress",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 1,
                succeeded = 0,
                errored = 0,
                canceled = 0,
                expired = 0
            )
            // CANCEL response
            server.enqueueBatchResponse(
                id = batchId,
                processingStatus = "canceling",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 0,
                succeeded = 0,
                errored = 0,
                canceled = 1,
                expired = 0
            )

            client.messages().batches().create(
                BatchCreateParams.builder()
                    .addRequest(
                        BatchCreateParams.Request.builder()
                            .customId("req-1")
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
            )

            client.messages().batches().retrieve(
                BatchRetrieveParams.builder().messageBatchId(batchId).build()
            )

            client.messages().batches().cancel(
                BatchCancelParams.builder().messageBatchId(batchId).build()
            )

            val traces = analyzeSpans()
            assertTracesCount(3, traces)

            assertEquals("batch.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batch.retrieve", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batch.cancel", traces[2].attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            // All traces should have the batch ID in the response
            for (trace in traces) {
                assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            }
        }
    }

    // ============ HELPER METHODS ============

    /**
     * Enqueues a mock response representing an Anthropic MessageBatch object.
     */
    private fun MockWebServer.enqueueBatchResponse(
        id: String,
        processingStatus: String,
        createdAt: Long,
        expiresAt: Long,
        processing: Int,
        succeeded: Int,
        errored: Int,
        canceled: Int,
        expired: Int,
    ) {
        enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "$id",
                    "type": "message_batch",
                    "processing_status": "$processingStatus",
                    "created_at": $createdAt,
                    "expires_at": $expiresAt,
                    "request_counts": {
                        "processing": $processing,
                        "succeeded": $succeeded,
                        "errored": $errored,
                        "canceled": $canceled,
                        "expired": $expired
                    }
                }
            """.trimIndent()))
    }

    /**
     * Builds a [TracyHttpRequestBody.Empty] request with given path segments and HTTP method,
     * used for unit-testing operation detection.
     */
    private fun buildRequest(segments: List<String>, method: String) =
        TracyHttpRequestBody.Empty.asRequestView(
            contentType = null,
            url = TracyHttpUrlImpl(
                scheme = "https",
                host = "api.anthropic.com",
                pathSegments = segments,
                parameters = object : TracyQueryParameters {
                    override fun queryParameter(name: String): String? = null
                    override fun queryParameterValues(name: String): List<String?> = emptyList()
                }
            ),
            method = method
        )

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
