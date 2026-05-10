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
class AnthropicBatchesTracingTest : BaseAnthropicTracingTest() {

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
                trace.attributes[AttributeKey.longKey("http.status_code")],
                "http.status_code should reflect the HTTP error status"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
