/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.instrument
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that [AnthropicLLMTracingAdapter] correctly detects the Anthropic API type from the
 * request URL and sets `anthropic.api.type` accordingly, using a [MockWebServer] so no real
 * API key is required.
 */
class AnthropicApiTypeDetectionTest : BaseAnthropicTracingTest() {

    // ── messages endpoint ────────────────────────────────────────────────────

    @Test
    fun `test anthropic api type is messages for v1-messages endpoint`() = runTest {
        withMockServer { server ->
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            val params = MessageCreateParams.builder()
                .addUserMessage("Hello")
                .maxTokens(100L)
                .model(Model.CLAUDE_HAIKU_4_5)
                .build()
            client.messages().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "messages",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type must be 'messages' for /v1/messages"
            )
        }
    }

    @Test
    fun `test span name is Anthropic-generation for messages endpoint`() = runTest {
        withMockServer { server ->
            server.enqueue(messagesResponse())

            val client = createAnthropicClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            val params = MessageCreateParams.builder()
                .addUserMessage("Hello")
                .maxTokens(100L)
                .model(Model.CLAUDE_HAIKU_4_5)
                .build()
            client.messages().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            assertEquals("Anthropic-generation", traces.first().name)
        }
    }

    // ── batches endpoint ─────────────────────────────────────────────────────

    @Test
    fun `test anthropic api type is batches for v1-messages-batches endpoint`() = runTest {
        withMockServer { server ->
            server.enqueue(batchCreateResponse())

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(AnthropicLLMTracingAdapter()))
                .build()

            val requestBody = BATCH_REQUEST_BODY.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .addHeader("x-api-key", MOCK_API_KEY)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "batches",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type must be 'batches' for /v1/messages/batches"
            )
        }
    }

    @Test
    fun `test span name is Anthropic-batch for batches endpoint`() = runTest {
        withMockServer { server ->
            server.enqueue(batchCreateResponse())

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(AnthropicLLMTracingAdapter()))
                .build()

            val requestBody = BATCH_REQUEST_BODY.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .addHeader("x-api-key", MOCK_API_KEY)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            assertEquals("Anthropic-batch", traces.first().name)
        }
    }

    @Test
    fun `test gen_ai operation name is non-empty for batches endpoint`() = runTest {
        withMockServer { server ->
            server.enqueue(batchCreateResponse())

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(AnthropicLLMTracingAdapter()))
                .build()

            val requestBody = BATCH_REQUEST_BODY.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .addHeader("x-api-key", MOCK_API_KEY)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val name = traces.first().name
            assertNotNull(name, "Span name (gen_ai.operation.name) must not be null for batches")
            assertTrue(name.isNotEmpty(), "Span name (gen_ai.operation.name) must not be empty for batches")
        }
    }

    @Test
    fun `test batch response attributes are extracted`() = runTest {
        withMockServer { server ->
            server.enqueue(batchCreateResponse())

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(AnthropicLLMTracingAdapter()))
                .build()

            val requestBody = BATCH_REQUEST_BODY.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .addHeader("x-api-key", MOCK_API_KEY)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "msgbatch_test123",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.id")]
            )
            assertEquals(
                "in_progress",
                trace.attributes[AttributeKey.stringKey("gen_ai.batch.processing_status")]
            )
            assertEquals(
                2L,
                trace.attributes[AttributeKey.longKey("gen_ai.batch.request_counts.processing")]
            )
        }
    }

    @Test
    fun `test batch request count attribute is set`() = runTest {
        withMockServer { server ->
            server.enqueue(batchCreateResponse())

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(AnthropicLLMTracingAdapter()))
                .build()

            val requestBody = BATCH_REQUEST_BODY.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .addHeader("x-api-key", MOCK_API_KEY)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                2L,
                trace.attributes[AttributeKey.longKey("gen_ai.batch.requests_count")],
                "gen_ai.batch.requests_count should reflect the number of requests in the batch body"
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun messagesResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "id": "msg_test123",
              "type": "message",
              "role": "assistant",
              "model": "claude-haiku-4-5",
              "content": [
                {
                  "type": "text",
                  "text": "Hello!"
                }
              ],
              "stop_reason": "end_turn",
              "usage": {
                "input_tokens": 5,
                "output_tokens": 2
              }
            }
            """.trimIndent()
        )

    private fun batchCreateResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "id": "msgbatch_test123",
              "type": "message_batch",
              "processing_status": "in_progress",
              "request_counts": {
                "processing": 2,
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
        )

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"

        private val BATCH_REQUEST_BODY = """
            {
              "requests": [
                {
                  "custom_id": "req-1",
                  "params": {
                    "model": "claude-haiku-4-5",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hello"}]
                  }
                },
                {
                  "custom_id": "req-2",
                  "params": {
                    "model": "claude-haiku-4-5",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hi"}]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
