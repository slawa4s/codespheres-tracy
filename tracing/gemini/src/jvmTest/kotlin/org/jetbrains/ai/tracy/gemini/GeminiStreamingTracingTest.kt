/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for Gemini streaming (`streamGenerateContent`) tracing using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("gemini")
class GeminiStreamingTracingTest : BaseAITracingTest() {

    @Test
    fun `streamGenerateContent accumulates text and sets response attributes`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.0-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("Hello world", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
            assertEquals("STOP", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
            assertEquals("resp-abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("gemini-2.0-flash-001", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals(10L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(20L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
        }
    }

    @Test
    fun `non-streaming generateContent URL does not trigger streaming path`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1},"modelVersion":"gemini-2.0-flash-001","responseId":"resp-xyz"}"""
                    )
            )

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.0-flash:generateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            // Non-streaming path — response attributes are set via handleResponseAttributes
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter())

    private fun MockWebServer.enqueueStreamingResponse() {
        val chunk1 = """{"candidates":[{"content":{"parts":[{"text":"Hello "}],"role":"model"},"index":0}],"modelVersion":"gemini-2.0-flash-001","responseId":"resp-abc"}"""
        val chunk2 = """{"candidates":[{"content":{"parts":[{"text":"world"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":20,"totalTokenCount":30},"modelVersion":"gemini-2.0-flash-001","responseId":"resp-abc"}"""
        val body = "data: $chunk1\r\n\r\ndata: $chunk2\r\n\r\n"

        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
    }
}
