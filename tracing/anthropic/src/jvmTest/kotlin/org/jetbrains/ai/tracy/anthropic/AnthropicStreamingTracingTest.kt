/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
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
import kotlin.test.assertTrue

/** Well-formed Anthropic SSE stream covering message_start, content_block_delta, and message_delta events. */
private val MOCK_STREAMING_SSE = """
event: message_start
data: {"type":"message_start","message":{"id":"msg_01abc","type":"message","role":"assistant","model":"claude-3-haiku-20240307","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: ping
data: {"type":"ping"}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world!"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":8}}

event: message_stop
data: {"type":"message_stop"}

""".trimIndent()

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicStreamingTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    @Test
    fun `test streaming request populates all response attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(MOCK_STREAMING_SSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(
                        """
                        {
                            "model": "claude-3-haiku-20240307",
                            "max_tokens": 100,
                            "messages": [{"role": "user", "content": "Say hi"}],
                            "stream": true
                        }
                        """.trimIndent().toRequestBody("application/json".toMediaType())
                    )
                    .build()
            ).execute().use { response ->
                response.body?.string()
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // response metadata from message_start
            assertEquals("msg_01abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("claude-3-haiku-20240307", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals("assistant", trace.attributes[AttributeKey.stringKey("gen_ai.response.role")])

            // output type is always "message" for streaming responses
            assertEquals("message", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])

            // token usage: input from message_start, output from message_delta
            assertEquals(12L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
            assertEquals(8L, trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])

            // accumulated text content from content_block_delta events
            assertEquals("Hello, world!", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])

            // stop reason from message_delta
            val finishReasons = trace.attributes[AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")]
            assertNotNull(finishReasons, "gen_ai.response.finish_reasons must be set")
            assertTrue(finishReasons.contains("end_turn"))

            // streaming flag set by base class and api type set in handleStreaming
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.streaming")])
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
        }
    }

    @Test
    fun `test non-streaming request still uses JSON response path`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "msg_01xyz",
                            "type": "message",
                            "role": "assistant",
                            "model": "claude-3-haiku-20240307",
                            "content": [{"type": "text", "text": "Hello!"}],
                            "stop_reason": "end_turn",
                            "usage": {"input_tokens": 10, "output_tokens": 5}
                        }
                        """.trimIndent()
                    )
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(
                        """
                        {
                            "model": "claude-3-haiku-20240307",
                            "max_tokens": 100,
                            "messages": [{"role": "user", "content": "Say hi"}]
                        }
                        """.trimIndent().toRequestBody("application/json".toMediaType())
                    )
                    .build()
            ).execute().use { response ->
                response.body?.string()
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // non-streaming path reads response from JSON body
            assertEquals("msg_01xyz", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("Hello!", trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
        }
    }

    @Test
    fun `test streaming request with only message_start event still sets metadata`() = runTest {
        val partialSse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_partial","type":"message","role":"assistant","model":"claude-3-haiku-20240307","content":[],"usage":{"input_tokens":5,"output_tokens":1}}}

        """.trimIndent()

        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody(partialSse)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .post(
                        """{"model":"claude-3-haiku-20240307","max_tokens":10,"messages":[{"role":"user","content":"Hi"}],"stream":true}"""
                            .toRequestBody("application/json".toMediaType())
                    )
                    .build()
            ).execute().use { response ->
                response.body?.string()
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("msg_partial", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("claude-3-haiku-20240307", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals("assistant", trace.attributes[AttributeKey.stringKey("gen_ai.response.role")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(5L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        }
    }
}
