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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON = "application/json".toMediaType()
private const val MOCK_API_KEY = "mock-api-key"

/**
 * MockWebServer-based tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler]
 * and [org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter] streaming support.
 *
 * No real API keys required — all requests are intercepted by the mock server.
 */
@Tag("gemini")
class GeminiContentGenHandlerTest : BaseAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter()).newBuilder().build()

    private fun MockWebServer.enqueueStreamingResponse(vararg sseEvents: String) {
        val body = sseEvents.joinToString("\n\n") { "data: $it" } + "\n\n"
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
    }

    private fun MockWebServer.enqueueJsonResponse(json: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json)
        )
    }

    // ============ isStreamingRequest ============

    @Test
    fun `streamGenerateContent URL is recognised as a streaming request`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r1","usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            // streaming span attributes set by handleStreaming
            assertEquals("r1", trace!!.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    @Test
    fun `generateContent URL is not recognised as a streaming request`() = runTest {
        withMockServer { server ->
            server.enqueueJsonResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r2","usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:generateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            // non-streaming: response id comes from handleResponseAttributes
            assertEquals("r2", trace!!.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    // ============ gemini.api.type ============

    @Test
    fun `content gen requests set gemini_api_type to models`() = runTest {
        withMockServer { server ->
            server.enqueueJsonResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"}}],"modelVersion":"gemini-2.5-flash","responseId":"r3"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:generateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("models", trace!!.attributes[AttributeKey.stringKey("gemini.api.type")])
        }
    }

    @Test
    fun `streaming content gen requests set gemini_api_type to models`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r4"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("models", trace!!.attributes[AttributeKey.stringKey("gemini.api.type")])
        }
    }

    // ============ handleStreaming attribute extraction ============

    @Test
    fun `streaming response id is extracted from SSE events`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}],"modelVersion":"gemini-2.5-flash","responseId":"stream-resp-001"}""",
                """{"candidates":[{"content":{"parts":[{"text":" World"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2},"modelVersion":"gemini-2.5-flash","responseId":"stream-resp-001"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("stream-resp-001", trace!!.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    @Test
    fun `streaming response model is extracted from SSE events`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash-001","responseId":"r5","usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("gemini-2.5-flash-001", trace!!.attributes[AttributeKey.stringKey("gen_ai.response.model")])
        }
    }

    @Test
    fun `streaming token counts are extracted from usageMetadata`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}],"modelVersion":"gemini-2.5-flash","responseId":"r6"}""",
                """{"candidates":[{"content":{"parts":[{"text":" there"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":7,"totalTokenCount":17},"modelVersion":"gemini-2.5-flash","responseId":"r6"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Hi there"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals(10L, trace!!.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(7L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
        }
    }

    @Test
    fun `streaming finish reason is extracted from last event candidate`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Done"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1},"modelVersion":"gemini-2.5-flash","responseId":"r7"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Go"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("STOP", trace!!.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
        }
    }

    @Test
    fun `streaming content is accumulated across all SSE events`() = runTest {
        withMockServer { server ->
            server.enqueueStreamingResponse(
                """{"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}],"modelVersion":"gemini-2.5-flash","responseId":"r8"}""",
                """{"candidates":[{"content":{"parts":[{"text":" "}],"role":"model"}}],"modelVersion":"gemini-2.5-flash","responseId":"r8"}""",
                """{"candidates":[{"content":{"parts":[{"text":"World"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":3},"modelVersion":"gemini-2.5-flash","responseId":"r8"}"""
            )
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.5-flash:streamGenerateContent"))
                    .post("""{"contents":[{"parts":[{"text":"Say hello world"}],"role":"user"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("Hello World", trace!!.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
        }
    }
}
