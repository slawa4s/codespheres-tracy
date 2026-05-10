/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON = "application/json".toMediaType()

/**
 * MockWebServer-based tests for Anthropic Message Batches API tracing.
 *
 * Verifies that requests to `/v1/messages/batches` set `anthropic.api.type = "batches"`
 * without attempting to parse the body as a Messages API payload.
 */
@Tag("anthropic")
class AnthropicBatchesTracingTest : BaseAITracingTest() {

    private fun makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    @Test
    fun batchesErrorResponseSetsApiTypeAndHttpStatus() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"requests array is empty"}}""")
            )

            val client = makeInstrumentedClient()
            val body = """{"requests":[]}""".toRequestBody(JSON)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(body)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the batches request")

            assertEquals(
                "batches",
                trace!!.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type should be 'batches' for /v1/messages/batches requests"
            )
            assertEquals(
                400L,
                trace.attributes[AttributeKey.longKey("http.response.status_code")],
                "http.response.status_code should reflect the HTTP error status"
            )
        }
    }

    @Test
    fun batchCreateErrorSetsErrorType() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"requests array is empty"}}""")
            )

            val client = makeInstrumentedClient()
            val body = """{"requests":[]}""".toRequestBody(JSON)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(body)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the batches request")

            assertEquals(
                "400",
                trace!!.attributes[AttributeKey.stringKey("error.type")],
                "error.type should be set to the HTTP status code string on error spans"
            )
        }
    }

    @Test
    fun batchCreateSetsOperationName() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_abc123","type":"message_batch","processing_status":"in_progress","request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},"ended_at":null,"created_at":"2024-09-24T18:37:24.100435Z","expires_at":"2024-09-25T18:37:24.100435Z","archived_at":null,"cancel_initiated_at":null,"results_url":null}""")
            )

            val client = makeInstrumentedClient()
            val body = """{"requests":[{"custom_id":"req-1","params":{"model":"claude-opus-4-5","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}}]}""".toRequestBody(JSON)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(body)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the batch create request")

            assertEquals(
                "create_batch",
                trace!!.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "gen_ai.operation.name should be 'create_batch' for POST /v1/messages/batches"
            )
        }
    }

    @Test
    fun batchCreateResponseSetsBatchAttributes() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "msgbatch_abc123",
                            "type": "message_batch",
                            "processing_status": "in_progress",
                            "created_at": 1714404061,
                            "expires_at": 1714490461,
                            "request_counts": {
                                "processing": 5,
                                "succeeded": 2,
                                "errored": 1,
                                "canceled": 0,
                                "expired": 0
                            }
                        }
                        """.trimIndent()
                    )
            )

            val client = makeInstrumentedClient()
            val body = """{"requests":[{"custom_id":"req1","params":{"model":"claude-3-haiku-20240307","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}}]}""".toRequestBody(JSON)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(body)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val span = traces.firstOrNull()
            assertNotNull(span, "Expected a span for the batches request")

            assertEquals(
                "msgbatch_abc123",
                span!!.attributes[AttributeKey.stringKey("anthropic.batch.id")],
                "anthropic.batch.id should be set from batch response id"
            )
            assertEquals(
                "message_batch",
                span.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be set from batch response type field"
            )
            assertEquals(
                "in_progress",
                span.attributes[AttributeKey.stringKey("anthropic.batch.processing_status")],
                "anthropic.batch.processing_status should be set"
            )
            assertEquals(
                1714404061L,
                span.attributes[AttributeKey.longKey("anthropic.batch.created_at")],
                "anthropic.batch.created_at should be set"
            )
            assertEquals(
                1714490461L,
                span.attributes[AttributeKey.longKey("anthropic.batch.expires_at")],
                "anthropic.batch.expires_at should be set"
            )
            assertEquals(
                5L,
                span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.processing")],
                "anthropic.batch.request_counts.processing should be set"
            )
            assertEquals(
                2L,
                span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.succeeded")],
                "anthropic.batch.request_counts.succeeded should be set"
            )
            assertEquals(
                1L,
                span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.errored")],
                "anthropic.batch.request_counts.errored should be set"
            )
            assertEquals(
                0L,
                span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.canceled")],
                "anthropic.batch.request_counts.canceled should be set"
            )
            assertEquals(
                0L,
                span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.expired")],
                "anthropic.batch.request_counts.expired should be set"
            )
        }
    }

    @Test
    fun batchCreateSetsRequestBatchSize() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_01","type":"message_batch","processing_status":"in_progress","request_counts":{"processing":2,"succeeded":0,"errored":0,"canceled":0,"expired":0},"ended_at":null,"created_at":"2025-01-01T00:00:00Z","expires_at":"2025-01-02T00:00:00Z","archived_at":null,"cancel_initiated_at":null,"results_url":null}""")
            )

            val client = makeInstrumentedClient()
            val body = """
                {
                  "requests": [
                    {"custom_id":"req-1","params":{"model":"claude-3-5-haiku-20241022","max_tokens":10,"messages":[{"role":"user","content":"Hello"}]}},
                    {"custom_id":"req-2","params":{"model":"claude-3-5-haiku-20241022","max_tokens":10,"messages":[{"role":"user","content":"World"}]}}
                  ]
                }
            """.trimIndent().toRequestBody(JSON)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(body)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the batches create request")

            assertEquals(
                2L,
                trace!!.attributes[AttributeKey.longKey("anthropic.batch.request_size")],
                "anthropic.batch.request_size should equal the number of entries in the requests array"
            )
        }
    }

    @Test
    fun batchDeleteSetsOperationName() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_abc123","deleted":true}""")
            )

            val client = makeInstrumentedClient()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123"))
                    .delete()
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the batch delete request")

            assertEquals(
                "delete_batch",
                trace!!.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "gen_ai.operation.name should be 'delete_batch' for DELETE /v1/messages/batches/{id}"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
