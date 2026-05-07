/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [BatchesAnthropicApiEndpointHandler].
 *
 * Uses [MockWebServer] and a plain [OkHttpClient] instrumented with [AnthropicLLMTracingAdapter]
 * (no Anthropic API key is required).
 *
 * Covers two scenarios:
 * - **lifecycle**: BATCH_CREATE, BATCH_RETRIEVE, BATCH_CANCEL, BATCH_LIST
 * - **invalid_empty_requests**: CREATE with an empty requests array
 */
class BatchesAnthropicApiEndpointHandlerTest : BaseAITracingTest() {

    // ============ HELPERS ============

    private fun MockWebServer.instrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    private fun jsonBody(json: String) =
        json.trimIndent().toRequestBody("application/json".toMediaType())

    private fun MockWebServer.enqueueJson(code: Int = 200, body: String) {
        enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent())
        )
    }

    private fun MockWebServer.enqueueBatch(
        id: String = "msgbatch_abc123",
        processingStatus: String = "in_progress",
        createdAt: String = "2025-01-01T00:00:00Z",
        expiresAt: String = "2025-01-02T00:00:00Z",
        processing: Int = 5,
        succeeded: Int = 3,
        errored: Int = 1,
        canceled: Int = 0,
        expired: Int = 0,
    ) = enqueueJson(
        body = """
            {
              "id": "$id",
              "type": "message_batch",
              "processing_status": "$processingStatus",
              "created_at": "$createdAt",
              "expires_at": "$expiresAt",
              "request_counts": {
                "processing": $processing,
                "succeeded": $succeeded,
                "errored": $errored,
                "canceled": $canceled,
                "expired": $expired
              }
            }
        """
    )

    // ============ lifecycle: BATCH_CREATE ============

    @Test
    fun `lifecycle - BATCH_CREATE sets correct operation name and batch attributes`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val batchId = "msgbatch_create_01"
            val createdAt = "2025-01-01T10:00:00Z"
            val expiresAt = "2025-01-02T10:00:00Z"

            server.enqueueBatch(
                id = batchId,
                processingStatus = "in_progress",
                createdAt = createdAt,
                expiresAt = expiresAt,
                processing = 10,
                succeeded = 0,
                errored = 0,
                canceled = 0,
                expired = 0,
            )

            val requestBody = """
                {
                  "requests": [
                    {"custom_id": "req-1", "params": {"model": "claude-3-5-haiku-latest", "max_tokens": 100, "messages": [{"role": "user", "content": "Hello"}]}},
                    {"custom_id": "req-2", "params": {"model": "claude-3-5-haiku-latest", "max_tokens": 100, "messages": [{"role": "user", "content": "World"}]}},
                    {"custom_id": "req-3", "params": {"model": "claude-3-5-haiku-latest", "max_tokens": 100, "messages": [{"role": "user", "content": "Foo"}]}}
                  ]
                }
            """
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .post(jsonBody(requestBody))
                .build()
            client.newCall(request).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(createdAt, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
            assertEquals(expiresAt, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    // ============ invalid_empty_requests: BATCH_CREATE with empty requests array ============

    @Test
    fun `invalid_empty_requests - BATCH_CREATE with empty requests array sets batch size to zero`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            server.enqueueJson(
                code = 400,
                body = """
                    {
                      "type": "error",
                      "error": {
                        "type": "invalid_request_error",
                        "message": "requests must not be empty"
                      }
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .post(jsonBody("""{"requests": []}"""))
                .build()

            try {
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // may throw depending on OkHttp config
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
        }
    }

    // ============ lifecycle: BATCH_RETRIEVE ============

    @Test
    fun `lifecycle - BATCH_RETRIEVE sets correct operation name and batch attributes`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val batchId = "msgbatch_retrieve_01"

            server.enqueueBatch(
                id = batchId,
                processingStatus = "ended",
                processing = 0,
                succeeded = 8,
                errored = 2,
                canceled = 0,
                expired = 0,
            )

            val request = Request.Builder()
                .url(server.url("/v1/messages/batches/$batchId"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(8L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
        }
    }

    // ============ lifecycle: BATCH_CANCEL ============

    @Test
    fun `lifecycle - BATCH_CANCEL sets correct operation name and batch attributes`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val batchId = "msgbatch_cancel_01"

            server.enqueueBatch(
                id = batchId,
                processingStatus = "canceling",
                processing = 3,
                succeeded = 2,
                errored = 0,
                canceled = 0,
                expired = 0,
            )

            val request = Request.Builder()
                .url(server.url("/v1/messages/batches/$batchId/cancel"))
                .post(jsonBody("{}"))
                .build()
            client.newCall(request).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("canceling", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ============ lifecycle: BATCH_LIST ============

    @Test
    fun `lifecycle - BATCH_LIST sets correct operation name`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            server.enqueueJson(
                body = """
                    {
                      "data": [
                        {
                          "id": "msgbatch_list_01",
                          "type": "message_batch",
                          "processing_status": "ended",
                          "created_at": "2025-01-01T00:00:00Z",
                          "expires_at": "2025-01-02T00:00:00Z",
                          "request_counts": {
                            "processing": 0,
                            "succeeded": 5,
                            "errored": 0,
                            "canceled": 0,
                            "expired": 0
                          }
                        }
                      ],
                      "has_more": false,
                      "first_id": "msgbatch_list_01",
                      "last_id": "msgbatch_list_01"
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    // ============ COMMON: anthropic.api.type is always "batches" ============

    @Test
    fun `anthropic api type is always batches regardless of route`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            // BATCH_CREATE
            server.enqueueBatch(id = "msgbatch_type_01")
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(jsonBody("""{"requests": []}"""))
                    .build()
            ).execute().close()

            // BATCH_RETRIEVE
            server.enqueueBatch(id = "msgbatch_type_02")
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_type_02"))
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            assertTrue(
                traces.all { it.attributes[AttributeKey.stringKey("anthropic.api.type")] == "batches" },
                "All batch traces should have anthropic.api.type = 'batches'"
            )
        }
    }

    // ============ COMMON: error responses ============

    @Test
    fun `error response is traced with ERROR status for batches endpoint`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            server.enqueueJson(
                code = 404,
                body = """
                    {
                      "type": "error",
                      "error": {
                        "type": "not_found_error",
                        "message": "No such batch: msgbatch_notfound"
                      }
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/messages/batches/msgbatch_notfound"))
                .get()
                .build()

            try {
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // may throw depending on OkHttp config
            }

            val trace = analyzeSpans().first()
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
        }
    }

    // ============ route detection unit tests ============

    @Test
    fun `detectRoute correctly identifies all four routes`() {
        val handler = BatchesAnthropicApiEndpointHandler()
        val emptyParams = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }

        fun url(path: String) = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.anthropic.com",
            port = 443,
            pathSegments = path.split("/").filter { it.isNotEmpty() },
            parameters = emptyParams,
        )

        assertEquals(BatchesAnthropicApiEndpointHandler.BatchRoute.CREATE, handler.detectRoute(url("/v1/messages/batches"), "POST"))
        assertEquals(BatchesAnthropicApiEndpointHandler.BatchRoute.LIST, handler.detectRoute(url("/v1/messages/batches"), "GET"))
        assertEquals(BatchesAnthropicApiEndpointHandler.BatchRoute.RETRIEVE, handler.detectRoute(url("/v1/messages/batches/msgbatch_abc123"), "GET"))
        assertEquals(BatchesAnthropicApiEndpointHandler.BatchRoute.CANCEL, handler.detectRoute(url("/v1/messages/batches/msgbatch_abc123/cancel"), "POST"))
    }
}
