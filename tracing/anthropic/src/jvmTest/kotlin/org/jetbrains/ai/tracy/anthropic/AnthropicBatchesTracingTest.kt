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
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
            assertEquals(BATCH_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        }
    }

    // ---- batch list -------------------------------------------------------------

    @Test
    fun `test batch list sets operation name`() = runTest {
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
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
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
            // gen_ai.operation.name must be set on error spans using only the URL + HTTP method.
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
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
