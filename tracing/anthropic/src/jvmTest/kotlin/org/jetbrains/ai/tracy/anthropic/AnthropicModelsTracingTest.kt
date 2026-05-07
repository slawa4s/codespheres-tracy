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
import kotlin.test.assertNull

/**
 * Unit tests for Anthropic Models API tracing using MockWebServer.
 *
 * These tests verify that the correct `anthropic.api.type`, `gen_ai.operation.name`,
 * and model-specific attributes are set for each models endpoint variant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicModelsTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- models retrieve --------------------------------------------------------

    @Test
    fun `test models retrieve sets operation name and model attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_OBJECT_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/$MODEL_ID"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(MODEL_ID, trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals(MODEL_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals(MODEL_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.model.id")])
            assertEquals("Claude 3.5 Sonnet", trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")])
            assertEquals("2024-10-22T00:00:00Z", trace.attributes[AttributeKey.stringKey("gen_ai.response.model.created_at")])
            assertEquals(200000L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")])
            assertEquals(8192L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_output_tokens")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.batch")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.citations")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.image_input")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.pdf_input")])
        }
    }

    // ---- models list ------------------------------------------------------------

    @Test
    fun `test models list sets operation name and api type`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_LIST_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            // No per-model attributes on a list response
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model.id")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    // ---- models do not bleed into regular messages endpoint ---------------------

    @Test
    fun `test regular messages endpoint is unaffected by models handler`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MESSAGE_RESPONSE)
            )

            val requestBody = """
                {
                    "model": "claude-3-5-sonnet-20241022",
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
            assertNull(trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    companion object {
        private const val MODEL_ID = "claude-3-5-sonnet-20241022"

        private val MODEL_OBJECT_RESPONSE = """
            {
                "id": "$MODEL_ID",
                "display_name": "Claude 3.5 Sonnet",
                "created_at": "2024-10-22T00:00:00Z",
                "type": "model",
                "max_input_tokens": 200000,
                "max_tokens": 8192,
                "capabilities": {
                    "batch": {"supported": true},
                    "citations": {"supported": true},
                    "image_input": {"supported": true},
                    "pdf_input": {"supported": false},
                    "structured_outputs": {"supported": true},
                    "code_execution": {"supported": false},
                    "thinking": {"supported": false},
                    "effort": {"supported": false},
                    "context_management": {"supported": false}
                }
            }
        """.trimIndent()

        private val MODEL_LIST_RESPONSE = """
            {
                "data": [
                    {"id": "$MODEL_ID", "type": "model", "display_name": "Claude 3.5 Sonnet", "created_at": "2024-10-22T00:00:00Z", "max_input_tokens": 200000, "max_tokens": 8192}
                ],
                "first_id": "$MODEL_ID",
                "last_id": "$MODEL_ID",
                "has_more": false
            }
        """.trimIndent()

        private val MESSAGE_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello!"}],
                "model": "claude-3-5-sonnet-20241022",
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

    }
}
