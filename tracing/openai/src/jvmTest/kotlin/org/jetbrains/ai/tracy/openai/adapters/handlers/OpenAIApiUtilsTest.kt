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
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON = "application/json".toMediaType()

/**
 * Tests for [OpenAIApiUtils] using [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class OpenAIApiUtilsTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder().build()

    @Test
    fun `tracy_response_object is set from body object field`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "chatcmpl-abc123",
                            "object": "chat.completion",
                            "model": "gpt-4o-mini",
                            "choices": []
                        }
                        """.trimIndent()
                    )
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/chat/completions"))
                    .post("""{"model":"gpt-4o-mini","messages":[]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("chat.completion", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
            // GEN_AI_OPERATION_NAME must still be set for backwards compatibility
            assertEquals("chat.completion", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `tracy_response_object is set for responses API object type`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "resp_abc123",
                            "object": "response",
                            "model": "gpt-4o-mini",
                            "output": []
                        }
                        """.trimIndent()
                    )
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/responses"))
                    .post("""{"model":"gpt-4o-mini","input":"hi"}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("response", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
