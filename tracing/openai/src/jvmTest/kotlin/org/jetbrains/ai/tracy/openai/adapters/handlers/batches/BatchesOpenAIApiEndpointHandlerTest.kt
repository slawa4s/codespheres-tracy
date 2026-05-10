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

private val JSON = "application/json".toMediaType()
private const val MOCK_API_KEY = "mock-api-key"

/**
 * Tests for [BatchesOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder().build()

    private fun MockWebServer.enqueueBatchResponse(
        id: String = "batch_abc123",
        status: String = "validating",
        createdAt: Long = 1_700_000_000L,
        total: Int = 5,
        completed: Int = 3,
        failed: Int = 1,
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
                      "created_at": $createdAt,
                      "request_counts": {"total": $total, "completed": $completed, "failed": $failed}
                    }
                    """.trimIndent()
                )
        )
    }

    // ============ CREATE: POST /v1/batches ============

    @Test
    fun `CREATE sets openai_api_type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post("""{"input_file_id":"file-abc","endpoint":"/v1/chat/completions","completion_window":"24h"}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `CREATE parses input_file_id, endpoint, completion_window from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """{"input_file_id":"file-xyz","endpoint":"/v1/chat/completions","completion_window":"24h"}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("file-xyz", trace.attributes[AttributeKey.stringKey("tracy.request.batch.input_file.id")])
            assertEquals("/v1/chat/completions", trace.attributes[AttributeKey.stringKey("tracy.request.batch.endpoint")])
            assertEquals("24h", trace.attributes[AttributeKey.stringKey("tracy.request.batch.completion_window")])
        }
    }

    @Test
    fun `CREATE parses output_expires_after from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """{"input_file_id":"file-1","endpoint":"/v1/chat/completions","completion_window":"24h","output_expires_after":{"anchor":"req_110","seconds":86400}}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("req_110", trace.attributes[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")])
            assertEquals(86400L, trace.attributes[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")])
        }
    }

    @Test
    fun `CREATE parses metadata keys from request body`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post(
                        """{"input_file_id":"file-1","endpoint":"/v1/chat/completions","completion_window":"24h","metadata":{"customer_id":"cust-1","job":"nightly"}}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            val metaKeys = trace.attributes[AttributeKey.stringKey("tracy.request.metadata.keys")]
            assertNotNull(metaKeys)
            // keys order may vary, so check both are present
            assert(metaKeys!!.contains("customer_id")) { "Expected 'customer_id' in metadata keys: $metaKeys" }
            assert(metaKeys.contains("job")) { "Expected 'job' in metadata keys: $metaKeys" }
        }
    }

    @Test
    fun `CREATE response sets batch id, status, created_at, and request_counts`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse(
                id = "batch_create_001",
                status = "validating",
                createdAt = 1_700_000_001L,
                total = 10,
                completed = 7,
                failed = 2,
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches"))
                    .post("""{"input_file_id":"file-abc","endpoint":"/v1/chat/completions","completion_window":"24h"}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batch_create_001", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals(1_700_000_001L, trace.attributes[AttributeKey.longKey("tracy.batch.created_at")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(7L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    // ============ RETRIEVE: GET /v1/batches/{batch_id} ============

    @Test
    fun `RETRIEVE sets openai_api_type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse(id = "batch_ret_001", status = "completed")

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_ret_001"))
                    .get()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `RETRIEVE response sets batch id, status, and request_counts`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse(
                id = "batch_ret_002",
                status = "completed",
                createdAt = 1_700_100_000L,
                total = 100,
                completed = 100,
                failed = 0,
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_ret_002"))
                    .get()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batch_ret_002", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
            assertEquals(1_700_100_000L, trace.attributes[AttributeKey.longKey("tracy.batch.created_at")])
            assertEquals(100L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.total")])
            assertEquals(100L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.completed")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.batch.request_counts.failed")])
        }
    }

    // ============ CANCEL: POST /v1/batches/{batch_id}/cancel ============

    @Test
    fun `CANCEL sets openai_api_type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse(id = "batch_cancel_001", status = "cancelling")

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_cancel_001/cancel"))
                    .post("".toRequestBody(null))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batches.cancel", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `CANCEL response sets batch id and status`() = runTest {
        withMockServer { server ->
            server.enqueueBatchResponse(id = "batch_cancel_002", status = "cancelling")

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/batches/batch_cancel_002/cancel"))
                    .post("".toRequestBody(null))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("batch_cancel_002", trace.attributes[AttributeKey.stringKey("tracy.batch.id")])
            assertEquals("cancelling", trace.attributes[AttributeKey.stringKey("tracy.batch.status")])
        }
    }
}
