/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.anthropic.clients.instrument as instrumentSdkClient
import org.jetbrains.ai.tracy.anthropic.clients.tryPatchAllOkHttpClients
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

/**
 * MockWebServer-based tests for Anthropic Message Batches API tracing.
 *
 * These tests do not require a real Anthropic API key or network access.
 */
@Tag("anthropic")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicBatchTracingTest : BaseAITracingTest() {

    private val jsonContentType = "application/json".toMediaType()

    private fun buildClient(): OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ===== anthropic.api.type and gen_ai.operation.name =====

    @Test
    fun `messages API sets anthropic api type to messages and operation name to chat`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post("""{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches create sets anthropic api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post("""{"requests":[]}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches retrieve sets anthropic api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches cancel sets anthropic api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(processingStatus = "canceling")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123/cancel"))
                    .post("{}".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches results sets anthropic api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/x-jsonl")
                    .setBody("")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123/results"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.results", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches delete sets operation name to batches delete`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_abc123","type":"message_batch_deleted","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches delete sets output type to message batch deleted`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_abc123","type":"message_batch_deleted","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_abc123"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("message_batch_deleted", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
        }
    }

    // ===== Request attribute extraction =====

    @Test
    fun `batches create extracts request batch size`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse()

            val batchBody = """
                {
                  "requests": [
                    {"custom_id":"req-1","params":{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"hi"}]}},
                    {"custom_id":"req-2","params":{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"hello"}]}}
                  ]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(batchBody.toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
        }
    }

    @Test
    fun `batches create emits gen ai request batch size`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse()

            val batchBody = """
                {
                  "requests": [
                    {"custom_id":"r1","params":{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"a"}]}},
                    {"custom_id":"r2","params":{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"b"}]}},
                    {"custom_id":"r3","params":{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"c"}]}}
                  ]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post(batchBody.toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
        }
    }

    // ===== Response attribute extraction =====

    @Test
    fun `batches create extracts batch response attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(
                id = "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
                processingStatus = "in_progress",
                createdAt = "2024-09-24T18:37:24.100435Z",
                expiresAt = "2024-09-25T18:37:24.100435Z",
                processing = 100,
                succeeded = 50,
                errored = 1,
                canceled = 0,
                expired = 0
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches"))
                    .post("""{"requests":[]}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("message_batch", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals("2024-09-24T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
            assertEquals("2024-09-25T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
            assertEquals(100L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(50L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `batches retrieve extracts batch response attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(
                id = "msgbatch_retrieve_xyz",
                processingStatus = "ended",
                processing = 0,
                succeeded = 5,
                errored = 0,
                canceled = 0,
                expired = 0
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/batches/msgbatch_retrieve_xyz"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("message_batch", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("msgbatch_retrieve_xyz", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
        }
    }

    // ===== SDK instrumentation path =====

    /**
     * Verifies that [instrumentSdkClient] (the SDK reflection path via [patchOpenAICompatibleClient]) attaches
     * the tracing interceptor to the same [OkHttpClient] instance used by the Anthropic SDK's internal
     * BatchServiceImpl.  A 400 response is enqueued so the request reaches the server and the span
     * can be validated without a real API key.
     *
     * If this test fails with "span NOT FOUND" it confirms that BatchServiceImpl uses a different
     * [OkHttpClient] instance than the one patched — the root cause of Scenario 0
     * (anthropic/batches/invalid_empty_requests span NOT FOUND in the evaluator).
     */
    @Test
    fun `sdk path records span for batches create with 400 error`() = runTest {
        withMockServer { server ->
            val sdkClient = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString().trimEnd('/'))
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(5))
                .build()
            instrumentSdkClient(sdkClient)

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"requests must not be empty"}}""")
            )

            try {
                sdkClient.messages().batches().create(
                    BatchCreateParams.builder().requests(emptyList()).build()
                )
            } catch (_: Exception) {
                // SDK throws BadRequestException for 400 responses — expected
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()
            assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(400L, span.attributes[AttributeKey.longKey("http.response.status_code")])
            assertNotNull(span.attributes[AttributeKey.stringKey("gen_ai.error.type")])
        }
    }

    /**
     * Verifies that [instrumentSdkClient] forces lazy initialisation of `BatchServiceImpl` so that
     * a successful batches.create call still produces a span with the correct operation name and
     * response attributes. Complements [sdk path records span for batches create with 400 error],
     * which only covers the error path.
     */
    @Test
    fun `sdk path records span for batches create with 200 response`() = runTest {
        withMockServer { server ->
            val sdkClient = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString().trimEnd('/'))
                .apiKey("test-key")
                .timeout(Duration.ofSeconds(5))
                .build()
            instrumentSdkClient(sdkClient)

            server.enqueueBatchResponse(
                id = "msgbatch_lazy_init",
                processingStatus = "in_progress"
            )

            try {
                sdkClient.messages().batches().create(
                    BatchCreateParams.builder().requests(emptyList()).build()
                )
            } catch (_: Exception) {
                // SDK may throw on empty requests — the span should still be recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()
            assertEquals("batches.create", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertNotNull(span.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
        }
    }

    // ===== tryPatchAllOkHttpClients JDK-wrapper unwrapping =====

    @Test
    fun `tryPatchAllOkHttpClients patches OkHttpClient directly wrapped in Optional`() {
        val okHttpClient = OkHttpClient.Builder().build()
        val holder = OptionalWrappedOkHttpClient(okHttpClient)
        val interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
        tryPatchAllOkHttpClients(holder, interceptor)
        assertNotNull(okHttpClient.interceptors.find { it is OpenTelemetryOkHttpInterceptor },
            "Interceptor should be attached to OkHttpClient unwrapped from Optional")
    }

    @Test
    fun `tryPatchAllOkHttpClients patches OkHttpClient directly wrapped in AtomicReference`() {
        val okHttpClient = OkHttpClient.Builder().build()
        val holder = AtomicReferenceWrappedOkHttpClient(okHttpClient)
        val interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
        tryPatchAllOkHttpClients(holder, interceptor)
        assertNotNull(okHttpClient.interceptors.find { it is OpenTelemetryOkHttpInterceptor },
            "Interceptor should be attached to OkHttpClient unwrapped from AtomicReference")
    }

    @Test
    fun `tryPatchAllOkHttpClients patches OkHttpClient nested inside Optional holder`() {
        val okHttpClient = OkHttpClient.Builder().build()
        val inner = InnerHolderWithOkHttpClient(okHttpClient)
        val holder = OptionalWrappedHolder(inner)
        val interceptor = OpenTelemetryOkHttpInterceptor(adapter = AnthropicLLMTracingAdapter())
        tryPatchAllOkHttpClients(holder, interceptor)
        assertNotNull(okHttpClient.interceptors.find { it is OpenTelemetryOkHttpInterceptor },
            "Interceptor should be attached to OkHttpClient nested inside Optional<Holder>")
    }

    // ===== Helpers =====

    private class OptionalWrappedOkHttpClient(client: OkHttpClient) {
        val wrappedClient: Optional<OkHttpClient> = Optional.of(client)
    }

    private class AtomicReferenceWrappedOkHttpClient(client: OkHttpClient) {
        val wrappedClient: AtomicReference<OkHttpClient> = AtomicReference(client)
    }

    private class InnerHolderWithOkHttpClient(val httpClient: OkHttpClient)

    private class OptionalWrappedHolder(inner: InnerHolderWithOkHttpClient) {
        val holder: Optional<InnerHolderWithOkHttpClient> = Optional.of(inner)
    }

    private fun MockWebServer.enqueueBatchResponse(
        id: String = "msgbatch_abc123",
        processingStatus: String = "in_progress",
        createdAt: String = "2024-09-24T18:37:24.100435Z",
        expiresAt: String = "2024-09-25T18:37:24.100435Z",
        processing: Int = 0,
        succeeded: Int = 0,
        errored: Int = 0,
        canceled: Int = 0,
        expired: Int = 0,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "$id",
                      "type": "message_batch",
                      "processing_status": "$processingStatus",
                      "request_counts": {
                        "processing": $processing,
                        "succeeded": $succeeded,
                        "errored": $errored,
                        "canceled": $canceled,
                        "expired": $expired
                      },
                      "created_at": "$createdAt",
                      "expires_at": "$expiresAt"
                    }
                    """.trimIndent()
                )
        )
    }
}
