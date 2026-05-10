/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [BatchesOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * These tests do not require a real OpenAI API key or network access.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private val jsonContentType = "application/json".toMediaType()

    // ===== openai.api.type and gen_ai.operation.name =====

    @Test
    fun `batches create sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """{"input_file_id":"file-xyz","endpoint":"/v1/chat/completions","completion_window":"24h"}"""
                            .toRequestBody(jsonContentType)
                    )
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches retrieve sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_abc"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches cancel sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_abc", status = "cancelling")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_abc/cancel"))
                    .post("".toRequestBody(null))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches list sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchListResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ===== Per-route attribute extraction =====

    @Test
    fun `batches create extracts request attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_new")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """
                        {
                            "input_file_id": "file-abc123",
                            "endpoint": "/v1/chat/completions",
                            "completion_window": "24h",
                            "output_expires_after": {"anchor": "req_time", "seconds": 86400},
                            "metadata": {"project": "myapp", "env": "prod"}
                        }
                        """.trimIndent().toRequestBody(jsonContentType)
                    )
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("file-abc123", trace.attributes[AttributeKey.stringKey("tracy.request.batch.input_file.id")])
            assertEquals("/v1/chat/completions", trace.attributes[AttributeKey.stringKey("tracy.request.batch.endpoint")])
            assertEquals("24h", trace.attributes[AttributeKey.stringKey("tracy.request.batch.completion_window")])
            assertEquals("req_time", trace.attributes[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")])
            assertEquals(86400L, trace.attributes[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")])
            // metadata keys should be sorted and comma-joined
            assertEquals("env,project", trace.attributes[AttributeKey.stringKey("tracy.request.metadata.keys")])
        }
    }

    @Test
    fun `batches create extracts response attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_resp", status = "validating", total = 10, completed = 5, failed = 2)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """{"input_file_id":"file-xyz","endpoint":"/v1/chat/completions","completion_window":"24h"}"""
                            .toRequestBody(jsonContentType)
                    )
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batch_resp", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.batch.created_at")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(5L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `batches retrieve extracts response attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_ret", status = "completed", total = 3, completed = 3, failed = 0)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_ret"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batch_ret", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    @Test
    fun `batches cancel extracts response attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueBatchResponse(id = "batch_can", status = "cancelling")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_can/cancel"))
                    .post("".toRequestBody(null))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("batch_can", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("cancelling", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun MockWebServer.enqueueBatchResponse(
        id: String,
        status: String = "validating",
        total: Int = 0,
        completed: Int = 0,
        failed: Int = 0,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "id": "$id",
                        "object": "batch",
                        "status": "$status",
                        "created_at": 1700000000,
                        "request_counts": {"total": $total, "completed": $completed, "failed": $failed}
                    }
                    """.trimIndent()
                )
        )
    }

    private fun MockWebServer.enqueueBatchListResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[],"has_more":false}""")
        )
    }
}
