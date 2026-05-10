/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * MockWebServer-based tests that verify [instrument] properly instruments the
 * `messages().batches()` sub-client, including via the [patchClientByFieldScan] fallback,
 * so that SDK-level batch requests produce spans with the expected attributes.
 */
@Tag("anthropic")
class AnthropicBatchesSDKClientTracingTest : BaseAITracingTest() {

    @Test
    fun instrumentedSDKClientEmitsSpanForBatchCreate() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"type":"error","error":{"type":"invalid_request_error","message":"requests array is empty"}}"""
                    )
            )

            val client = AnthropicOkHttpClient.builder()
                .baseUrl(server.url("/").toString())
                .apiKey(MOCK_API_KEY)
                .maxRetries(0)
                .build()
            instrument(client)

            val params = BatchCreateParams.builder()
                .requests(emptyList())
                .build()

            try {
                client.messages().batches().create(params)
            } catch (_: Exception) {
                // Expected: the server returned 400
            }

            val traces = analyzeSpans()
            val span = traces.firstOrNull()
            assertNotNull(span, "Expected a span for the batches request via instrumented AnthropicClient")

            assertEquals(
                "batches",
                span!!.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type should be 'batches' for /v1/messages/batches requests"
            )
            assertEquals(
                "400",
                span.attributes[AttributeKey.stringKey("error.type")],
                "error.type should be set to the HTTP status code string on error spans"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
