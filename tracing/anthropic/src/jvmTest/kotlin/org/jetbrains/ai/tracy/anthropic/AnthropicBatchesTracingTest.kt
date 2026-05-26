/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.interceptors.instrument
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for Anthropic Batches API tracing using MockWebServer.
 *
 * These tests verify that the correct `gen_ai.operation.name` and batch-specific attributes
 * are set for each batch endpoint variant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicBatchesTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- batch create -----------------------------------------------------------

    @Test
    fun `test batch create sets operation name and batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(BATCH_OBJECT_RESPONSE)
            )

            val requestBody = """
                {
                    "requests": [
                        {
                            "custom_id": "test-1",
                            "params": {
                                "model": "claude-haiku-4-5-20251001",
                                "max_tokens": 100,
                                "messages": [{"role": "user", "content": "Hello"}]
                            }
                        }
                    ]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.request.requests.size")])
            assertEquals("test-1", trace.attributes[AttributeKey.stringKey("gen_ai.request.requests.0.custom_id")])
            assertEquals(
                """{"model":"claude-haiku-4-5-20251001","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
                trace.attributes[AttributeKey.stringKey("gen_ai.request.requests.0.params")]
            )
            assertEquals(BATCH_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ---- batch list -------------------------------------------------------------

    @Test
    fun `test batch list sets operation name and pagination attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(BATCH_LIST_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
            assertEquals("msgbatch_01", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.first_id")])
            assertEquals("msgbatch_02", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.last_id")])
            // Single-object batch attributes must NOT be set for list responses
            assertNull(
                trace.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type must not be set for list responses"
            )
        }
    }

    // ---- batch retrieve ---------------------------------------------------------

    @Test
    fun `test batch retrieve sets operation name and batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(BATCH_OBJECT_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/$BATCH_ID"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(BATCH_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ---- batch cancel -----------------------------------------------------------

    @Test
    fun `test batch cancel sets operation name and batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(BATCH_OBJECT_RESPONSE.replace("in_progress", "canceling"))
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/$BATCH_ID/cancel"))
                    .addHeader("x-api-key", "test-key")
                    .post("".toRequestBody(null))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(BATCH_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("canceling", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ---- batch delete -----------------------------------------------------------

    @Test
    fun `test batch delete sets operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$BATCH_ID","type":"message_batch_deleted","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/$BATCH_ID"))
                    .addHeader("x-api-key", "test-key")
                    .delete()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ---- regular messages: operation name = "chat" ------------------------------

    @Test
    fun `test regular messages endpoint sets operation name to chat`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MESSAGE_RESPONSE)
            )

            val requestBody = """
                {
                    "model": "claude-haiku-4-5-20251001",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hi"}]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    // ---- error fallback: non-standard body (e.g. invalid empty requests) ---------

    @Test
    fun `test batch create with empty requests returns error span with error type fallback`() = runTest {
        withMockServer { server ->
            // Simulates the Anthropic API returning a 400 with a non-standard error body
            // (e.g. {"detail": "..."}) that lacks the standard {"error": {"type": "..."}} envelope.
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "At least one request is required in a message batch"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(400L, trace.attributes[AttributeKey.longKey("http.status_code")])
            // The fallback must populate error.type even though the body has no standard error envelope.
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
            // anthropic.api.type must survive on error-only spans for batch URLs.
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            // gen_ai.provider.name must be set on error spans as a safety net for the batch error path.
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            // gen_ai.operation.name must be set on error spans using only the URL + HTTP method.
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            // gen_ai.output.type must NOT be set on error spans — the early guard in
            // BatchesAnthropicApiEndpointHandler.handleResponseAttributes prevents it.
            assertNull(
                trace.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type must not be set on batch error spans"
            )
        }
    }

    @Test
    fun `test non-standard 5xx error body sets error type fallback to internal_error`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"message": "Internal server error"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(500L, trace.attributes[AttributeKey.longKey("http.status_code")])
            assertEquals("internal_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    @Test
    fun `test standard Anthropic error body takes precedence over fallback`() = runTest {
        withMockServer { server ->
            // Standard Anthropic error envelope - error.type should come from the body, not the fallback
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"model not found"}}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            // error.type from body takes precedence; value should match the body, not the fallback
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
            assertEquals("model not found", trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
        }
    }

    // ---- span-attribute fallback: api type read from span when ThreadLocal unavailable -----------

    /**
     * Verifies that `getResponseErrorBodyAttributes` reads `anthropic.api.type` from the span
     * (written by `handleRequestAttributes`) rather than relying solely on the ThreadLocal.
     *
     * This is tested indirectly: the span attribute is written first by `handleRequestAttributes`,
     * then `getResponseErrorBodyAttributes` can read it back. The redirect scenario in the next
     * test validates the URL-fallback path; this test validates the overall attribute presence
     * after the span-attribute lookup path is exercised.
     */
    @Test
    fun `test batch error span has anthropic api type set from span attribute fallback`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "error"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // anthropic.api.type is set by handleRequestAttributes (before the request is sent)
            // and is still present on the span when getResponseErrorBodyAttributes runs.
            assertEquals(
                "batches",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type must be present from span attribute fallback"
            )
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ---- redirect: api type preserved via ThreadLocal ---------------------------

    /**
     * Verifies that `anthropic.api.type` is still set correctly on an error span even when OkHttp
     * follows a redirect and the final response URL no longer contains a "batches" path segment.
     *
     * The ThreadLocal in [AnthropicLLMTracingAdapter] stores the API type detected from the
     * *original* request URL so that [LLMTracingAdapter.getResponseErrorBodyAttributes] does not need to re-parse
     * the (potentially rewritten) response URL.
     */
    @Test
    fun `test batch error span preserves api type after redirect to non-batch URL`() = runTest {
        withMockServer { server ->
            // First response: 307 redirect from /v1/messages/batches to /v1/error (no "batches" segment)
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", server.url("/v1/error").toString())
            )
            // Second response: 400 error at the redirect target (URL has no "batches" segment)
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "At least one request is required in a message batch"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(400L, trace.attributes[AttributeKey.longKey("http.status_code")])
            // anthropic.api.type must be "batches" even though the final response URL is /v1/error
            assertEquals(
                "batches",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "ThreadLocal must preserve api type across redirect"
            )
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    /**
     * Verifies that `gen_ai.operation.name` set during request processing is NOT overwritten by
     * a redirect whose final URL and method would cause `detectOperation()` to return a different
     * (incorrect) value.
     *
     * Scenario:
     *  - Client POSTs to `/v1/messages/batches` → `handleRequestAttributes` sets
     *    `gen_ai.operation.name = "batches.create"`.
     *  - Server responds with 302 redirecting to `/v1/messages/batches/<id>`.
     *  - OkHttp follows the redirect as GET (POST→GET after 302).
     *  - Server responds with 400 at the redirect target.
     *  - Without the fix, `detectOperation(url="/v1/messages/batches/<id>", method="GET")` would
     *    return `"batches.retrieve"`, overwriting the correct value.
     *  - With the fix, the span attribute set at request time is preserved.
     */
    @Test
    fun `test batch create operation name is preserved after redirect that changes URL and method`() = runTest {
        withMockServer { server ->
            val batchId = "msgbatch_redirect_test"
            // First response: 302 redirect from /v1/messages/batches to /v1/messages/batches/{id}
            // OkHttp will re-issue the request as GET (POST→GET on 302 by default)
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/v1/messages/batches/$batchId").toString())
            )
            // Second response: 400 at the redirect target (URL = /v1/messages/batches/{id}, method = GET)
            // detectOperation() on this URL+method would return "batches.retrieve" without the fix.
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "error at redirect target"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .addHeader("x-api-key", "test-key")
                    .post("""{"requests": []}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            // gen_ai.operation.name must be preserved from request time, not overwritten
            // by detectOperation() on the redirect target URL (which would give "batches.retrieve").
            assertEquals(
                "batches.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "gen_ai.operation.name must not be overwritten by detectOperation() after redirect"
            )
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    companion object {
        private const val BATCH_ID = "msgbatch_01HkcTjaV5uDC8jWR4ZsDV8d"

        private val BATCH_OBJECT_RESPONSE = """
            {
                "id": "$BATCH_ID",
                "type": "message_batch",
                "processing_status": "in_progress",
                "request_counts": {
                    "processing": 1,
                    "succeeded": 0,
                    "errored": 0,
                    "canceled": 0,
                    "expired": 0
                },
                "ended_at": null,
                "created_at": "2024-09-24T18:37:24.100435Z",
                "expires_at": "2024-09-25T18:37:24.100435Z",
                "cancel_initiated_at": null,
                "results_url": null
            }
        """.trimIndent()

        private val BATCH_LIST_RESPONSE = """
            {
                "data": [
                    {"id": "msgbatch_01", "type": "message_batch"},
                    {"id": "msgbatch_02", "type": "message_batch"}
                ],
                "has_more": false,
                "first_id": "msgbatch_01",
                "last_id": "msgbatch_02"
            }
        """.trimIndent()

        private val MESSAGE_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello!"}],
                "model": "claude-haiku-4-5-20251001",
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()
    }
}
