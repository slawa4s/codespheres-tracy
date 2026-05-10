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
                trace!!.attributes[AttributeKey.longKey("gen_ai.request.batch.size")],
                "gen_ai.request.batch.size should equal the number of entries in the requests array"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
