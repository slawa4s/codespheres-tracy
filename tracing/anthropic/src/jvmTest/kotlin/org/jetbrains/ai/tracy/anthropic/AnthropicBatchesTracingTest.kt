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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("anthropic")
class AnthropicBatchesTracingTest : BaseAITracingTest() {

    private val batchCreateResponse = """
        {
            "id": "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
            "type": "message_batch",
            "processing_status": "in_progress",
            "request_counts": {
                "processing": 3,
                "succeeded": 0,
                "errored": 0,
                "canceled": 0,
                "expired": 0
            },
            "ended_at": null,
            "created_at": "2024-08-20T18:37:24.100435Z",
            "expires_at": "2024-08-21T18:37:24.100435Z",
            "cancel_initiated_at": null,
            "results_url": null
        }
    """.trimIndent()

    private val batchRetrieveResponse = """
        {
            "id": "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
            "type": "message_batch",
            "processing_status": "ended",
            "request_counts": {
                "processing": 0,
                "succeeded": 2,
                "errored": 0,
                "canceled": 0,
                "expired": 1
            },
            "ended_at": "2024-08-20T18:45:00.000000Z",
            "created_at": "2024-08-20T18:37:24.100435Z",
            "expires_at": "2024-08-21T18:37:24.100435Z",
            "cancel_initiated_at": null,
            "results_url": "https://api.anthropic.com/v1/messages/batches/msgbatch_013Zva2CMHLNnXjNJJKqJ2EF/results"
        }
    """.trimIndent()

    private val batchCancelResponse = """
        {
            "id": "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
            "type": "message_batch",
            "processing_status": "canceling",
            "request_counts": {
                "processing": 1,
                "succeeded": 1,
                "errored": 0,
                "canceled": 1,
                "expired": 0
            },
            "ended_at": null,
            "created_at": "2024-08-20T18:37:24.100435Z",
            "expires_at": "2024-08-21T18:37:24.100435Z",
            "cancel_initiated_at": "2024-08-20T18:42:00.000000Z",
            "results_url": null
        }
    """.trimIndent()

    private fun createInstrumentedOkHttpClient(): OkHttpClient {
        val okHttpClient = OkHttpClient.Builder().build()
        return instrument(okHttpClient, AnthropicLLMTracingAdapter())
    }

    private fun jsonBody(body: String) =
        body.toRequestBody("application/json".toMediaType())

    @Test
    fun `test batch create sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(batchCreateResponse)
            )

            val requestBody = """
                {
                    "requests": [
                        {
                            "custom_id": "req-1",
                            "params": {
                                "model": "claude-opus-4-5",
                                "max_tokens": 1024,
                                "messages": [{"role": "user", "content": "Hello"}]
                            }
                        },
                        {
                            "custom_id": "req-2",
                            "params": {
                                "model": "claude-opus-4-5",
                                "max_tokens": 1024,
                                "messages": [{"role": "user", "content": "World"}]
                            }
                        },
                        {
                            "custom_id": "req-3",
                            "params": {
                                "model": "claude-opus-4-5",
                                "max_tokens": 1024,
                                "messages": [{"role": "user", "content": "!"}]
                            }
                        }
                    ]
                }
            """.trimIndent()

            val client = createInstrumentedOkHttpClient()
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches").toString())
                .post(jsonBody(requestBody))
                .build()

            client.newCall(request).execute().use { }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.request.batch.size")])
        }
    }

    @Test
    fun `test batch create response attributes are extracted`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(batchCreateResponse)
            )

            val requestBody = """
                {
                    "requests": [
                        {
                            "custom_id": "req-1",
                            "params": {
                                "model": "claude-opus-4-5",
                                "max_tokens": 1024,
                                "messages": [{"role": "user", "content": "Hi"}]
                            }
                        }
                    ]
                }
            """.trimIndent()

            val client = createInstrumentedOkHttpClient()
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches").toString())
                .post(jsonBody(requestBody))
                .build()

            client.newCall(request).execute().use { }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals("2024-08-20T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
            assertEquals("2024-08-21T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test batch retrieve sets operation name and response attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(batchRetrieveResponse)
            )

            val client = createInstrumentedOkHttpClient()
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches/msgbatch_013Zva2CMHLNnXjNJJKqJ2EF").toString())
                .get()
                .build()

            client.newCall(request).execute().use { }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("ended", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals("2024-08-20T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
            assertEquals("2024-08-21T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test batch cancel sets operation name and response attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(batchCancelResponse)
            )

            val client = createInstrumentedOkHttpClient()
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches/msgbatch_013Zva2CMHLNnXjNJJKqJ2EF/cancel").toString())
                .post("".toRequestBody())
                .build()

            client.newCall(request).execute().use { }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("canceling", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals("2024-08-20T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
            assertEquals("2024-08-21T18:37:24.100435Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
        }
    }

    @Test
    fun `test batch create without batch size when requests field absent`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(batchCreateResponse)
            )

            val client = createInstrumentedOkHttpClient()
            // Send an empty JSON body (no "requests" field)
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches").toString())
                .post(jsonBody("{}"))
                .build()

            client.newCall(request).execute().use { }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            // batch size should not be set when "requests" field is absent
            assertNotNull(trace)
        }
    }
}
