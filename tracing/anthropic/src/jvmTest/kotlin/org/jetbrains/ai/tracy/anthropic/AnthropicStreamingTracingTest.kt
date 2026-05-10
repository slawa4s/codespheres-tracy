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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON_MEDIA = "application/json".toMediaType()

/**
 * MockWebServer-based tests for Anthropic Messages streaming API tracing.
 *
 * Verifies that SSE events emitted by the streaming Messages endpoint are parsed correctly
 * and that all seven OTel GenAI attributes are populated on the span:
 * `gen_ai.response.id`, `gen_ai.output.type`, `gen_ai.response.role`, `gen_ai.response.model`,
 * `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, and `gen_ai.completion.0.content`.
 */
@Tag("anthropic")
class AnthropicStreamingTracingTest : BaseAITracingTest() {

    private fun makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    private val sseResponse = buildString {
        append("event: message_start\n")
        append("""data: {"type":"message_start","message":{"id":"msg_stream01","type":"message","role":"assistant","content":[],"model":"claude-3-5-haiku-20241022","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""")
        append("\n\n")
        append("event: content_block_start\n")
        append("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        append("\n\n")
        append("event: ping\n")
        append("""data: {"type":"ping"}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""")
        append("\n\n")
        append("event: content_block_delta\n")
        append("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" World"}}""")
        append("\n\n")
        append("event: content_block_stop\n")
        append("""data: {"type":"content_block_stop","index":0}""")
        append("\n\n")
        append("event: message_delta\n")
        append("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}""")
        append("\n\n")
        append("event: message_stop\n")
        append("""data: {"type":"message_stop"}""")
        append("\n\n")
    }

    @Test
    fun streamingResponseSetsResponseId() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseResponse)
            )

            val client = makeInstrumentedClient()
            val requestBody = """{"model":"claude-3-5-haiku-20241022","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
                .toRequestBody(JSON_MEDIA)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post(requestBody)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().use { it.body?.string() }

            val span = analyzeSpans().firstOrNull()
            assertNotNull(span, "Expected a span for the streaming request")

            assertEquals(
                "msg_stream01",
                span!!.attributes[AttributeKey.stringKey("gen_ai.response.id")],
                "gen_ai.response.id should be extracted from message_start"
            )
        }
    }

    @Test
    fun streamingResponseSetsOutputTypeAndRole() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseResponse)
            )

            val client = makeInstrumentedClient()
            val requestBody = """{"model":"claude-3-5-haiku-20241022","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
                .toRequestBody(JSON_MEDIA)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post(requestBody)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().use { it.body?.string() }

            val span = analyzeSpans().firstOrNull()
            assertNotNull(span, "Expected a span for the streaming request")

            assertEquals(
                "message",
                span!!.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be extracted from message_start.message.type"
            )
            assertEquals(
                "assistant",
                span.attributes[AttributeKey.stringKey("gen_ai.response.role")],
                "gen_ai.response.role should be extracted from message_start.message.role"
            )
        }
    }

    @Test
    fun streamingResponseSetsModelAndTokens() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseResponse)
            )

            val client = makeInstrumentedClient()
            val requestBody = """{"model":"claude-3-5-haiku-20241022","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
                .toRequestBody(JSON_MEDIA)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post(requestBody)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().use { it.body?.string() }

            val span = analyzeSpans().firstOrNull()
            assertNotNull(span, "Expected a span for the streaming request")

            assertEquals(
                "claude-3-5-haiku-20241022",
                span!!.attributes[AttributeKey.stringKey("gen_ai.response.model")],
                "gen_ai.response.model should be extracted from message_start.message.model"
            )
            assertEquals(
                10L,
                span.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")],
                "gen_ai.usage.input_tokens should be extracted from message_start.message.usage"
            )
            assertEquals(
                15L,
                span.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")],
                "gen_ai.usage.output_tokens should be extracted from message_delta.usage"
            )
        }
    }

    @Test
    fun streamingResponseAccumulatesTextContent() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseResponse)
            )

            val client = makeInstrumentedClient()
            val requestBody = """{"model":"claude-3-5-haiku-20241022","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
                .toRequestBody(JSON_MEDIA)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post(requestBody)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().use { it.body?.string() }

            val span = analyzeSpans().firstOrNull()
            assertNotNull(span, "Expected a span for the streaming request")

            assertEquals(
                "Hello World",
                span!!.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")],
                "gen_ai.completion.0.content should be the concatenation of all text_delta fragments"
            )
        }
    }

    @Test
    fun nonStreamingRequestIsNotTreatedAsStreaming() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"msg_abc","type":"message","role":"assistant","content":[{"type":"text","text":"Hi"}],"model":"claude-3-5-haiku-20241022","stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":3}}"""
                    )
            )

            val client = makeInstrumentedClient()
            val requestBody = """{"model":"claude-3-5-haiku-20241022","max_tokens":100,"messages":[{"role":"user","content":"Hi"}]}"""
                .toRequestBody(JSON_MEDIA)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post(requestBody)
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().use { it.body?.string() }

            val span = analyzeSpans().firstOrNull()
            assertNotNull(span, "Expected a span for the non-streaming request")

            // For non-streaming, getResponseBodyAttributes parses the JSON body directly
            assertEquals(
                "msg_abc",
                span!!.attributes[AttributeKey.stringKey("gen_ai.response.id")],
                "Non-streaming response id should be parsed from JSON body"
            )
            assertEquals(
                "Hi",
                span.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")],
                "Non-streaming completion content should be parsed from JSON body"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
