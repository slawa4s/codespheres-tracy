/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters

import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests that [org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter] emits the expected base span
 * attributes on every provider span: `gen_ai.provider.name`, `server.address`, `server.port`, and
 * `http.response.status_code`. Uses MockWebServer so no real API key is required.
 */
@Tag("openai")
class LLMTracingAdapterBaseAttributesTest : BaseOpenAITracingTest() {

    @Test
    fun `test server address and port are set from request URL`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(10),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MINIMAL_CHAT_COMPLETION_RESPONSE)
            )

            val params = ChatCompletionCreateParams.builder()
                .addUserMessage("Hello")
                .model(ChatModel.GPT_4O_MINI)
                .build()
            client.chat().completions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("localhost", trace.attributes[AttributeKey.stringKey("server.address")])
            assertEquals(server.port.toLong(), trace.attributes[AttributeKey.longKey("server.port")])
        }
    }

    @Test
    fun `test gen_ai provider name is set on every span`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(10),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MINIMAL_CHAT_COMPLETION_RESPONSE)
            )

            val params = ChatCompletionCreateParams.builder()
                .addUserMessage("Hello")
                .model(ChatModel.GPT_4O_MINI)
                .build()
            client.chat().completions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("openai", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
        }
    }

    @Test
    fun `test http response status code is set on successful response`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(10),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MINIMAL_CHAT_COMPLETION_RESPONSE)
            )

            val params = ChatCompletionCreateParams.builder()
                .addUserMessage("Hello")
                .model(ChatModel.GPT_4O_MINI)
                .build()
            client.chat().completions().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
            assertEquals(200L, trace.attributes[AttributeKey.longKey("http.status_code")])
        }
    }

    @Test
    fun `test http response status code is set on error response`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(10),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "error": {
                                "message": "Rate limit exceeded",
                                "type": "rate_limit_error",
                                "code": "rate_limit_exceeded"
                            }
                        }
                        """.trimIndent()
                    )
            )

            val params = ChatCompletionCreateParams.builder()
                .addUserMessage("Hello")
                .model(ChatModel.GPT_4O_MINI)
                .build()
            try {
                client.chat().completions().create(params)
            } catch (_: Exception) {
                // expected on error response
            }

            val traces = analyzeSpans()
            assertNotNull(traces.firstOrNull())
            val trace = traces.first()

            assertEquals(429L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
            assertEquals(429L, trace.attributes[AttributeKey.longKey("http.status_code")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"

        private val MINIMAL_CHAT_COMPLETION_RESPONSE = """
            {
                "id": "chatcmpl-test123",
                "object": "chat.completion",
                "created": 1677652288,
                "model": "gpt-4o-mini",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Hello!"},
                    "finish_reason": "stop"
                }],
                "usage": {
                    "prompt_tokens": 5,
                    "completion_tokens": 3,
                    "total_tokens": 8
                }
            }
        """.trimIndent()
    }
}
