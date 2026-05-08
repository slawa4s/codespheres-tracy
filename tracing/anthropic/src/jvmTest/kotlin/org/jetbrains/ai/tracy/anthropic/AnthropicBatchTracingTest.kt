/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.batches.BatchCancelParams
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.BatchRetrieveParams
import com.anthropic.services.blocking.messages.BatchService
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Mock batch response body returned by all three lifecycle endpoints. */
private val MOCK_BATCH_RESPONSE = """
{
  "id": "msgbatch_01abc",
  "type": "message_batch",
  "processing_status": "in_progress",
  "request_counts": {
    "processing": 3,
    "succeeded": 1,
    "errored": 0,
    "canceled": 0,
    "expired": 0
  },
  "created_at": "2024-09-24T18:37:24.100435Z",
  "expires_at": "2024-09-25T18:37:24.100435Z",
  "ended_at": null,
  "cancel_initiated_at": null,
  "results_url": null
}
""".trimIndent()

/** Batch response representing a canceling state. */
private val MOCK_BATCH_CANCEL_RESPONSE = """
{
  "id": "msgbatch_01abc",
  "type": "message_batch",
  "processing_status": "canceling",
  "request_counts": {
    "processing": 2,
    "succeeded": 1,
    "errored": 0,
    "canceled": 0,
    "expired": 0
  },
  "created_at": "2024-09-24T18:37:24.100435Z",
  "expires_at": "2024-09-25T18:37:24.100435Z",
  "ended_at": null,
  "cancel_initiated_at": "2024-09-24T18:40:00.000000Z",
  "results_url": null
}
""".trimIndent()

@Tag("anthropic")
class AnthropicBatchTracingTest : BaseAITracingTest() {

    private fun createClient(baseUrl: String) = AnthropicOkHttpClient.builder()
        .baseUrl(baseUrl)
        .apiKey("test-api-key")
        .timeout(Duration.ofSeconds(10))
        .build()

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Create
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test batch create sets gen_ai response batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(MOCK_BATCH_RESPONSE)
            )

            val client = createClient(server.url("/").toString())
            instrument(client)

            val params = BatchCreateParams.builder()
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("req-1")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(10)
                                .addUserMessage("Hi")
                                .build()
                        )
                        .build()
                )
                .build()

            try {
                client.messages().batches().create(params)
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertBatchAttributes(trace, expectedStatus = "in_progress", operationName = "batches.create")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Retrieve
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test batch retrieve sets gen_ai response batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(MOCK_BATCH_RESPONSE)
            )

            val client = createClient(server.url("/").toString())
            instrument(client)

            val params = BatchRetrieveParams.builder()
                .messageBatchId("msgbatch_01abc")
                .build()

            try {
                client.messages().batches().retrieve(params)
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertBatchAttributes(trace, expectedStatus = "in_progress", operationName = "batches.retrieve")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Cancel
    // ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `test batch cancel sets gen_ai response batch attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(MOCK_BATCH_CANCEL_RESPONSE)
            )

            val client = createClient(server.url("/").toString())
            instrument(client)

            val params = BatchCancelParams.builder()
                .messageBatchId("msgbatch_01abc")
                .build()

            try {
                client.messages().batches().cancel(params)
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertBatchAttributes(trace, expectedStatus = "canceling", operationName = "batches.cancel")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Error: empty requests (SDK-level)
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that a span is still exported when the Anthropic SDK makes a batch create
     * request that the server rejects with a 400 error.
     *
     * Note: `BatchCreateParams.builder().build()` (no requests at all) throws an
     * [IllegalStateException] at build-time before any HTTP call is made, so the OkHttp
     * interceptor never runs in that path. Instead, we use `requests(emptyList())` to produce
     * `{"requests":[]}`, which is a valid serialization that reaches the server and causes a 400
     * response. This exercises the same error-fallback code path that the raw-OkHttp test in
     * `AnthropicBatchesTracingTest` already covers.
     */
    @Test
    fun `test batch create with empty requests still emits error span`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "At least one request is required in a message batch"}""")
            )

            val client = createClient(server.url("/").toString())
            instrument(client)

            try {
                client.messages().batches().create(
                    BatchCreateParams.builder().requests(emptyList()).build()
                )
            } catch (_: Exception) {
                // SDK throws on 400 response; trace is already recorded by the OkHttp interceptor
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "batches",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type"
            )
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("error.type")],
                "error.type must be non-null"
            )
            assertEquals(
                400L,
                trace.attributes[AttributeKey.longKey("http.response.status_code")],
                "http.response.status_code"
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Error: pre-HTTP SDK exception (proxy path)
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the [BatchService] proxy installed by [instrument] emits an error span even
     * when the SDK throws **before** any HTTP call is attempted.
     *
     * The scenario is modelled after `BatchCreateParams.builder().build()` (no requests provided),
     * which throws [IllegalStateException] at build-time so the OkHttp interceptor never fires.
     * To exercise the proxy path directly, this test replaces the batch service inside the client
     * with a fake that throws [IllegalStateException] from `create()` without making any HTTP call,
     * then instruments the client so the proxy wraps the fake service.
     */
    @Test
    fun `test batch create proxy emits error span for pre-HTTP SDK exception`() = runTest {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())

            // Inject a fake BatchService that throws IllegalStateException inside create()
            // *before* calling instrument(). This simulates SDK validation errors (e.g., from
            // BatchCreateParams.builder().build()) that occur before any HTTP call.
            injectThrowingBatchService(client)

            // instrument() wraps the fake service with the proxy.
            instrument(client)

            val caughtException = runCatching {
                client.messages().batches().create(
                    // Use valid params – the fake service throws regardless.
                    BatchCreateParams.builder()
                        .addRequest(
                            BatchCreateParams.Request.builder()
                                .customId("req-1")
                                .params(
                                    BatchCreateParams.Request.Params.builder()
                                        .model(Model.CLAUDE_HAIKU_4_5)
                                        .maxTokens(10)
                                        .addUserMessage("Hi")
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            }.exceptionOrNull()

            assertNotNull(caughtException, "Expected an exception from the fake batch service")

            // No MockWebServer response was enqueued, confirming no HTTP call was made.
            assertEquals(0, server.requestCount, "No HTTP request should have been sent")

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "anthropic",
                trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")],
                "gen_ai.provider.name"
            )
            assertEquals(
                "batches",
                trace.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type"
            )
            assertEquals(
                "batches.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "gen_ai.operation.name"
            )
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("error.type")],
                "error.type must be set"
            )
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("server.address")],
                "server.address must be set"
            )
            assertNotNull(
                trace.attributes[AttributeKey.longKey("server.port")],
                "server.port must be set"
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Shared assertion helper
    // ──────────────────────────────────────────────────────────────────────────────────────────

    private fun assertBatchAttributes(
        trace: io.opentelemetry.sdk.trace.data.SpanData,
        expectedStatus: String,
        operationName: String,
    ) {
        // operation name
        assertEquals(
            operationName,
            trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
            "gen_ai.operation.name"
        )

        // gen_ai.output.type
        assertEquals(
            "message_batch",
            trace.attributes[AttributeKey.stringKey("gen_ai.output.type")],
            "gen_ai.output.type"
        )

        // gen_ai.response.id and gen_ai.response.batch.id both set from JSON "id"
        val batchId = trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")]
        assertNotNull(batchId, "gen_ai.response.batch.id must be present")
        assertEquals("msgbatch_01abc", batchId, "gen_ai.response.batch.id")
        assertEquals(batchId, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")], "gen_ai.response.id")

        // processing status
        assertEquals(
            expectedStatus,
            trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")],
            "gen_ai.response.batch.processing_status"
        )

        // timestamps
        assertNotNull(
            trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")],
            "gen_ai.response.batch.created_at must be present"
        )
        assertNotNull(
            trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")],
            "gen_ai.response.batch.expires_at must be present"
        )

        // request_counts
        assertNotNull(
            trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")],
            "gen_ai.response.batch.request_counts.processing must be present"
        )
        assertNotNull(
            trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")],
            "gen_ai.response.batch.request_counts.succeeded must be present"
        )
        assertNotNull(
            trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")],
            "gen_ai.response.batch.request_counts.errored must be present"
        )
        assertNotNull(
            trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")],
            "gen_ai.response.batch.request_counts.canceled must be present"
        )
        assertNotNull(
            trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")],
            "gen_ai.response.batch.request_counts.expired must be present"
        )
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // Test helpers
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the [BatchService] inside [client] with a dynamic proxy that throws
     * [IllegalStateException] from every `create(...)` overload without making any HTTP call.
     *
     * This simulates the situation where the Anthropic SDK raises a validation error before
     * the request is serialised and dispatched.
     */
    private fun injectThrowingBatchService(client: AnthropicClient) {
        val messagesService = client.messages()

        // Force lazy initialisation so the field has a real BatchServiceImpl value to read.
        val realBatchService = messagesService.batches()

        // Build a dynamic proxy that delegates everything to the real service except create(),
        // which always throws IllegalStateException to simulate a pre-HTTP SDK validation error.
        val throwingProxy = Proxy.newProxyInstance(
            realBatchService.javaClass.classLoader,
            arrayOf(BatchService::class.java),
        ) { _, method, args ->
            val actualArgs: Array<Any?> = args ?: emptyArray()
            if (method.name == "create") {
                throw IllegalStateException("requests is required, but was not set")
            }
            method.invoke(realBatchService, *actualArgs)
        } as BatchService

        // Swap the cached value inside the existing Lazy.
        var cls: Class<*>? = messagesService.javaClass
        while (cls != null) {
            try {
                val delegateField = cls.getDeclaredField("batches\$delegate")
                delegateField.isAccessible = true
                val lazyDelegate = delegateField.get(messagesService)
                (lazyDelegate as Lazy<*>).value
                var lazyCls: Class<*>? = lazyDelegate.javaClass
                while (lazyCls != null) {
                    try {
                        val valueField = lazyCls.getDeclaredField("_value")
                        valueField.isAccessible = true
                        valueField.set(lazyDelegate, throwingProxy)
                        return
                    } catch (_: NoSuchFieldException) {
                        lazyCls = lazyCls.superclass
                    }
                }
                error("Could not find _value field in ${lazyDelegate.javaClass.name}")
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        error("Could not find batches\$delegate field in ${messagesService.javaClass.name}")
    }
}
