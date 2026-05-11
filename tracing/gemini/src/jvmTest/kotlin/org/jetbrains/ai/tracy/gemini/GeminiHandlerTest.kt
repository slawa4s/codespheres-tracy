/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * MockWebServer-based tests for [GeminiLLMTracingAdapter] response parsing.
 *
 * These tests do not call real Gemini APIs and require no API keys.
 */
@Tag("gemini")
class GeminiHandlerTest : BaseAITracingTest() {
    private fun createInstrumentedClient(server: MockWebServer): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter()).newBuilder()
            .build()

    private fun postJson(client: OkHttpClient, url: String, body: String) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { /* consume */ }
    }

    @Test
    fun `generateContent response sets gen_ai_output_type and finish reasons`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient(server)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "candidates": [
                            {
                              "content": {"parts": [{"text": "Hello!"}], "role": "model"},
                              "finishReason": "STOP"
                            }
                          ],
                          "usageMetadata": {
                            "promptTokenCount": 5,
                            "candidatesTokenCount": 3,
                            "totalTokenCount": 8
                          },
                          "responseId": "resp-001",
                          "modelVersion": "gemini-2.5-flash-001"
                        }
                        """.trimIndent()
                    )
            )

            postJson(
                client,
                server.url("/v1beta/models/gemini-2.5-flash:generateContent").toString(),
                """{"contents":[{"parts":[{"text":"Hello"}],"role":"user"}]}"""
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals("message", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("resp-001", span.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("gemini-2.5-flash-001", span.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertNotNull(span.attributes[AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")])
            assertEquals("STOP", span.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
            assertEquals(5L, span.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(3L, span.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
            assertEquals(8L, span.attributes[AttributeKey.longKey("gen_ai.usage.total_tokens")])
        }
    }

    @Test
    fun `countTokens response sets gen_ai_usage_total_tokens from totalTokens field`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient(server)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"totalTokens": 42}""")
            )

            postJson(
                client,
                server.url("/v1beta/models/gemini-2.5-flash:countTokens").toString(),
                """{"contents":[{"parts":[{"text":"Count this"}],"role":"user"}]}"""
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals(42L, span.attributes[AttributeKey.longKey("gen_ai.usage.total_tokens")])
        }
    }

    @Test
    fun `embedContent response sets output type and dimension`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient(server)
            val values = (1..768).joinToString(",") { "0.$it" }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embedding": {"values": [$values]}}""")
            )

            postJson(
                client,
                server.url("/v1beta/models/gemini-embedding-001:predict").toString(),
                """{"instances":[{"content":"hello"}]}"""
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals("embedContent", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("embedding", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(1L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
            assertEquals(768L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }
}
