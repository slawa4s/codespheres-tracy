/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] and a mock API key — no real OpenAI API calls are made.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun instrumentedOkHttpClient() = OkHttpClient.Builder()
        .addInterceptor(OpenTelemetryOkHttpInterceptor(OpenAILLMTracingAdapter()))
        .build()

    @Test
    fun `test conversations endpoint sets openai api type attribute`() = runTest {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id": "conv_abc123", "object": "conversation", "created_at": 1699564595}""")
            )

            val body = """{"model": "gpt-4o"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .addHeader("Authorization", "Bearer $MOCK_API_KEY")
                .post(body)
                .build()

            client.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test conversations create response sets conversation id attribute`() = runTest {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            val conversationId = "conv_xyz789"
            val createdAt = 1699564595L

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "$conversationId",
                            "object": "conversation",
                            "created_at": $createdAt
                        }
                        """.trimIndent()
                    )
            )

            val body = """{"model": "gpt-4o"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .addHeader("Authorization", "Bearer $MOCK_API_KEY")
                .post(body)
                .build()

            client.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `test conversations request sets model attribute`() = runTest {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            val model = "gpt-4o-mini"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id": "conv_model_test", "object": "conversation", "created_at": 1699564595}""")
            )

            val body = """{"model": "$model"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .addHeader("Authorization", "Bearer $MOCK_API_KEY")
                .post(body)
                .build()

            client.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(model, trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `test conversations sub-path is still routed to conversations handler`() = runTest {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            val conversationId = "conv_retrieve_test"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id": "$conversationId", "object": "conversation", "created_at": 1699564595}""")
            )

            // GET /v1/conversations/{id} — retrieval sub-path
            val request = Request.Builder()
                .url(server.url("/v1/conversations/$conversationId"))
                .addHeader("Authorization", "Bearer $MOCK_API_KEY")
                .get()
                .build()

            client.newCall(request).execute().use { /* consume response */ }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Routed to ConversationsOpenAIApiEndpointHandler, not the ChatCompletions fallback
            assertNotNull(trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
