/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import com.google.genai.types.HttpOptions
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.gemini.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

import com.google.genai.Client as GeminiClient

/**
 * Tests the SSE streaming branch of
 * [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler].
 *
 * Pre-existing Gemini tests cover non-streaming `generateContent`; this suite focuses
 * specifically on the SSE accumulation logic that was added/extended for
 * `:streamGenerateContent` (and `?alt=sse`) responses.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Gemini API access required.
 */
@Tag("gemini")
class GeminiContentGenStreamingTracingTest : BaseAITracingTest() {

    @Test
    fun `streamed generateContent accumulates text and parses last chunk metadata`() = runTest {
        withMockServer { server ->
            val client = GeminiClient.builder()
                .apiKey(MOCK_API_KEY)
                .httpOptions(
                    HttpOptions.builder()
                        .baseUrl(server.url("/").toString().trimEnd('/'))
                        .build()
                )
                .build()
                .apply { instrument(this) }

            // Chunked SSE response. Last chunk carries responseId, modelVersion, usageMetadata
            // and the candidate finishReason — exactly what handleStreaming(...) extracts.
            val sseBody = """
                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"text":", world"}]}}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"!"}]},"finishReason":"STOP"}],"responseId":"resp_abc","modelVersion":"gemini-2.5-flash","usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"totalTokenCount":7}}

            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )

            client.models.generateContentStream("gemini-2.5-flash", "Say hi", null)
                .forEach { /* drain */ }

            val trace = analyzeSpans().first()
            assertEquals("Hello, world!", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
            assertEquals("STOP", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
            assertEquals("resp_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("gemini-2.5-flash", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(4L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-gemini-key"
    }
}
