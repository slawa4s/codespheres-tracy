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
 * Unit tests for Anthropic Models API tracing using MockWebServer.
 *
 * Verifies that `anthropic.api.type`, `gen_ai.operation.name`, `gen_ai.request.model`,
 * and `gen_ai.response.model` are set correctly for Models API endpoints. For model retrieval,
 * the URL alias (e.g., `claude-haiku-4-5`) is used for both request and response model
 * attributes, while the versioned id from the response body (e.g., `claude-haiku-4-5-20251001`)
 * is stored as `gen_ai.response.id` and `gen_ai.response.model.id`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicModelsTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- models list -----------------------------------------------------------

    @Test
    fun `test models list sets operation name to list`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODELS_LIST_RESPONSE)
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
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    // ---- models retrieve -------------------------------------------------------

    @Test
    fun `test models retrieve sets operation name and request model from URL alias`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_RETRIEVE_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/$MODEL_ALIAS"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(MODEL_ALIAS, trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `test models retrieve sets response model to URL alias not versioned id`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_RETRIEVE_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/$MODEL_ALIAS"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // gen_ai.response.model must equal the URL alias, not the versioned id
            assertEquals(MODEL_ALIAS, trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            // versioned id from the response body is stored under gen_ai.response.id and gen_ai.response.model.id
            assertEquals(MODEL_VERSIONED_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(MODEL_VERSIONED_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.model.id")])
        }
    }

    @Test
    fun `test models retrieve sets display name and created at attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_RETRIEVE_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/$MODEL_ALIAS"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model.created_at")])
        }
    }

    @Test
    fun `test models retrieve sets capability flags`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODEL_RETRIEVE_WITH_CAPABILITIES_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/$MODEL_ALIAS"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.batch")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.citations")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.pdf_input")])
        }
    }

    // ---- regular messages endpoint is unaffected by models handler -------------

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
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    companion object {
        private const val MODEL_ALIAS = "claude-haiku-4-5"
        private const val MODEL_VERSIONED_ID = "claude-haiku-4-5-20251001"

        private val MODELS_LIST_RESPONSE = """
            {
                "data": [
                    {
                        "type": "model",
                        "id": "$MODEL_VERSIONED_ID",
                        "display_name": "Claude Haiku 4 5",
                        "created_at": "2024-10-22T00:00:00Z"
                    }
                ],
                "has_more": false,
                "first_id": "$MODEL_VERSIONED_ID",
                "last_id": "$MODEL_VERSIONED_ID"
            }
        """.trimIndent()

        private val MODEL_RETRIEVE_RESPONSE = """
            {
                "type": "model",
                "id": "$MODEL_VERSIONED_ID",
                "display_name": "Claude Haiku 4 5",
                "created_at": "2024-10-22T00:00:00Z",
                "max_input_tokens": 200000,
                "max_tokens": 8192
            }
        """.trimIndent()

        private val MODEL_RETRIEVE_WITH_CAPABILITIES_RESPONSE = """
            {
                "type": "model",
                "id": "$MODEL_VERSIONED_ID",
                "display_name": "Claude Haiku 4 5",
                "created_at": "2024-10-22T00:00:00Z",
                "max_input_tokens": 200000,
                "max_tokens": 8192,
                "capabilities": {
                    "batch": {"supported": true},
                    "citations": {"supported": true},
                    "image_input": {"supported": true},
                    "pdf_input": {"supported": false}
                }
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
