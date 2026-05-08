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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for Anthropic Count Tokens API tracing using MockWebServer.
 *
 * Verifies that `anthropic.api.type = "count_tokens"`, `gen_ai.operation.name = "count_tokens"`,
 * `gen_ai.request.model`, and `gen_ai.usage.input_tokens` are set correctly.
 *
 * See: [Count Tokens API](https://docs.anthropic.com/en/api/messages-count-tokens)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicCountTokensTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- count_tokens: basic attributes -----------------------------------------

    @Test
    fun `test count_tokens sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(COUNT_TOKENS_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/count_tokens"))
                    .addHeader("x-api-key", "test-key")
                    .post(COUNT_TOKENS_REQUEST.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test count_tokens sets request model from request body`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(COUNT_TOKENS_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/count_tokens"))
                    .addHeader("x-api-key", "test-key")
                    .post(COUNT_TOKENS_REQUEST.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(MODEL, trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `test count_tokens sets input_tokens from response body`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(COUNT_TOKENS_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/count_tokens"))
                    .addHeader("x-api-key", "test-key")
                    .post(COUNT_TOKENS_REQUEST.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(42L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        }
    }

    @Test
    fun `test count_tokens sets response id when present`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(COUNT_TOKENS_RESPONSE_WITH_ID)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/count_tokens"))
                    .addHeader("x-api-key", "test-key")
                    .post(COUNT_TOKENS_REQUEST.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(RESPONSE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    @Test
    fun `test count_tokens does not set response id when absent`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(COUNT_TOKENS_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages/count_tokens"))
                    .addHeader("x-api-key", "test-key")
                    .post(COUNT_TOKENS_REQUEST.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    // ---- regular messages endpoint is unaffected --------------------------------

    @Test
    fun `test regular messages endpoint is unaffected by count_tokens handler`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MESSAGE_RESPONSE)
            )

            val requestBody = """
                {
                    "model": "$MODEL",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hi"}]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    companion object {
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val RESPONSE_ID = "ct_01XFDUDYJgAACzvnptvVoYEL"

        private val COUNT_TOKENS_REQUEST = """
            {
                "model": "$MODEL",
                "messages": [{"role": "user", "content": "Hello, Claude"}]
            }
        """.trimIndent()

        private val COUNT_TOKENS_RESPONSE = """
            {
                "input_tokens": 42
            }
        """.trimIndent()

        private val COUNT_TOKENS_RESPONSE_WITH_ID = """
            {
                "id": "$RESPONSE_ID",
                "input_tokens": 42
            }
        """.trimIndent()

        private val MESSAGE_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello!"}],
                "model": "$MODEL",
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()
    }
}
