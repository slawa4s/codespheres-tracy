/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters

import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.videos.VideoCreateParams
import com.openai.models.videos.VideoModel
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@Tag("openai")
class OpenAIApiTypeAttributeTest : BaseOpenAITracingTest() {

    @Test
    fun `test openai api type is chat_completions for chat completions endpoint`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

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
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "Hello!"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """.trimIndent()
                    )
            )

            client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(ChatModel.GPT_4O_MINI)
                    .addUserMessage("Hi")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("chat_completions", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test openai api type is responses for responses endpoint`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

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
                          "output": [
                            {
                              "type": "message",
                              "role": "assistant",
                              "content": [{"type": "output_text", "text": "Hello!"}]
                            }
                          ],
                          "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
                        }
                        """.trimIndent()
                    )
            )

            client.responses().create(
                ResponseCreateParams.builder()
                    .model(ChatModel.GPT_4O_MINI)
                    .input("Hi")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("responses", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test openai api type is images_generations for images generate endpoint`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "created": ${Instant.now().epochSecond},
                          "data": [{"url": "https://example.com/image.png", "revised_prompt": "A cat"}]
                        }
                        """.trimIndent()
                    )
            )

            client.images().generate(
                ImageGenerateParams.builder()
                    .model(ImageModel.DALL_E_2)
                    .prompt("A cat")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("images.generations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test openai api type is videos for videos endpoint`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "video-abc123",
                          "object": "video",
                          "status": "completed",
                          "created_at": ${Instant.now().epochSecond},
                          "model": "sora-2",
                          "prompt": "A cat playing"
                        }
                        """.trimIndent()
                    )
            )

            client.videos().create(
                VideoCreateParams.builder()
                    .model(VideoModel.SORA_2)
                    .prompt("A cat playing")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("videos", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test gen_ai operation name is chat for chat completions non-streaming request`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

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
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "Hello!"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """.trimIndent()
                    )
            )

            client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(ChatModel.GPT_4O_MINI)
                    .addUserMessage("Hi")
                    .build()
            )

            val trace = analyzeSpans().first()
            // operation name must be "chat", not the raw "chat.completion" object field from the response body
            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test gen_ai request stream is true when stream is set in chat completions request`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val sseBody = listOf(
                """data: {"id":"chatcmpl-abc123","object":"chat.completion.chunk","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}""",
                "",
                """data: {"id":"chatcmpl-abc123","object":"chat.completion.chunk","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}""",
                "",
                """data: {"id":"chatcmpl-abc123","object":"chat.completion.chunk","model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
                "",
                "data: [DONE]",
                "",
            ).joinToString("\n")

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody)
            )

            runCatching {
                client.chat().completions().createStreaming(
                    ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addUserMessage("Hi")
                        .build()
                ).use { stream ->
                    stream.stream().forEach { _ -> }
                }
            }

            val trace = analyzeSpans().first()
            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.request.stream")])
        }
    }

    @Test
    fun `test gen_ai request stream is absent for non-streaming chat completions request`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

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
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "Hello!"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """.trimIndent()
                    )
            )

            client.chat().completions().create(
                ChatCompletionCreateParams.builder()
                    .model(ChatModel.GPT_4O_MINI)
                    .addUserMessage("Hi")
                    .build()
            )

            val trace = analyzeSpans().first()
            // gen_ai.request.stream should be absent (not explicitly set to false) for non-streaming requests
            assertEquals(null, trace.attributes[AttributeKey.booleanKey("gen_ai.request.stream")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
