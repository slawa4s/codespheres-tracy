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
                span!!.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")],
                "gen_ai.response.batch.id should be set from batch response id"
            )
            assertEquals(
                "message_batch",
                span.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be set from batch response type field"
            )
            assertEquals(
                "in_progress",
                span.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")],
                "gen_ai.response.batch.processing_status should be set"
            )
            assertEquals(
                1714404061L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.created_at")],
                "gen_ai.response.batch.created_at should be set"
            )
            assertEquals(
                1714490461L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.expires_at")],
                "gen_ai.response.batch.expires_at should be set"
            )
            assertEquals(
                5L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")],
                "gen_ai.response.batch.request_counts.processing should be set"
            )
            assertEquals(
                2L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")],
                "gen_ai.response.batch.request_counts.succeeded should be set"
            )
            assertEquals(
                1L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")],
                "gen_ai.response.batch.request_counts.errored should be set"
            )
            assertEquals(
                0L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")],
                "gen_ai.response.batch.request_counts.canceled should be set"
            )
            assertEquals(
                0L,
                span.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")],
                "gen_ai.response.batch.request_counts.expired should be set"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
