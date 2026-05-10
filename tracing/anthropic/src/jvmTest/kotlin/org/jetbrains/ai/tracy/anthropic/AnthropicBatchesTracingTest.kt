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
    fun batchCreateSetsOperationName() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch_abc123","type":"message_batch","processing_status":"in_progress","request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},"ended_at":null,"created_at":"2024-09-24T18:37:24.100435Z","expires_at":"2024-09-25T18:37:24.100435Z","archived_at":null,"cancel_initiated_at":null,"results_url":null}""")
            )

            val client = makeInstrumentedClient()
            val body = """{"requests":[{"custom_id":"req-1","params":{"model":"claude-opus-4-5","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}}]}""".toRequestBody(JSON)

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
            assertNotNull(trace, "Expected a span for the batch create request")

            assertEquals(
                "create_batch",
                trace!!.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "gen_ai.operation.name should be 'create_batch' for POST /v1/messages/batches"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
