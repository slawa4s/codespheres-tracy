/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonString
import com.anthropic.models.messages.MessageCountTokensParams
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

/**
 * Tests that verify specific span attributes emitted by [AnthropicLLMTracingAdapter].
 * All tests use [MockWebServer] — no real API key is required.
 */
@Tag("anthropic")
class AnthropicAdapterAttributesTest : BaseAITracingTest() {

    private val testModel = Model.CLAUDE_HAIKU_4_5

    private fun createClient(url: String): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .baseUrl(url)
            .apiKey(MOCK_API_KEY)
            .timeout(Duration.ofSeconds(10))
            .build()
            .also { instrument(it) }

    // ---- Messages: routing attributes ----

    @Test
    fun `messages endpoint sets anthropic api type and operation name`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MESSAGES_RESPONSE))

            client.messages().create(basicMessageParams())

            val trace = analyzeSpans().first()
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ---- Messages: base infrastructure attributes ----

    @Test
    fun `messages response sets server address and port`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MESSAGES_RESPONSE))

            client.messages().create(basicMessageParams())

            val trace = analyzeSpans().first()
            assertEquals("localhost", trace.attributes[AttributeKey.stringKey("server.address")])
            assertEquals(server.port.toLong(), trace.attributes[AttributeKey.longKey("server.port")])
        }
    }

    @Test
    fun `messages response sets http status code`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MESSAGES_RESPONSE))

            client.messages().create(basicMessageParams())

            val trace = analyzeSpans().first()
            assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    // ---- Messages: tool type defaults to "custom" ----

    @Test
    fun `tool without explicit type field defaults to custom`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MESSAGES_RESPONSE))

            client.messages().create(
                MessageCreateParams.builder()
                    .addUserMessage("Hello")
                    .addTool(
                        Tool.builder()
                            .type(Tool.Type.CUSTOM)
                            .name("greet")
                            .description("Greet the user")
                            .inputSchema(
                                Tool.InputSchema.builder()
                                    .type(JsonString.of("object"))
                                    .build()
                            )
                            .build()
                    )
                    .maxTokens(10L)
                    .model(testModel)
                    .build()
            )

            val trace = analyzeSpans().first()
            // Anthropic tools have no explicit "type" field in the JSON payload;
            // the adapter must default to "custom" per OTel GenAI spec
            assertEquals("custom", trace.attributes[AttributeKey.stringKey("gen_ai.tool.0.type")])
            assertEquals("greet", trace.attributes[AttributeKey.stringKey("gen_ai.tool.0.name")])
        }
    }

    // ---- Messages: error handling ----

    @Test
    fun `error response sets error type attributes and error span status`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "type": "error",
                        "error": {
                            "type": "invalid_request_error",
                            "message": "max_tokens: Field required"
                        }
                    }
                """.trimIndent()))

            try {
                client.messages().create(basicMessageParams())
            } catch (_: Exception) {}

            val trace = analyzeSpans().first()
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("gen_ai.error.type")])
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
        }
    }

    // ---- Count tokens endpoint ----

    @Test
    fun `count tokens endpoint sets anthropic api type and operation name`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"input_tokens": 42}"""))

            client.messages().countTokens(
                MessageCountTokensParams.builder()
                    .addUserMessage("Hello")
                    .model(testModel)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("count_tokens", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `count tokens response captures input token count`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"input_tokens": 42}"""))

            client.messages().countTokens(
                MessageCountTokensParams.builder()
                    .addUserMessage("Hello")
                    .model(testModel)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(42L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        }
    }

    // ---- Batches endpoint ----

    @Test
    fun `batches create endpoint sets anthropic api type and operation name`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(BATCH_RESPONSE))

            client.messages().batches().create(
                BatchCreateParams.builder()
                    .addRequest(
                        BatchCreateParams.Request.builder()
                            .customId("req-0")
                            .params(
                                BatchCreateParams.Request.Params.builder()
                                    .addUserMessage("Hello")
                                    .maxTokens(10L)
                                    .model(testModel)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `batches create response sets processing status and request counts`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(BATCH_RESPONSE))

            client.messages().batches().create(
                BatchCreateParams.builder()
                    .addRequest(
                        BatchCreateParams.Request.builder()
                            .customId("req-0")
                            .params(
                                BatchCreateParams.Request.Params.builder()
                                    .addUserMessage("Hello")
                                    .maxTokens(10L)
                                    .model(testModel)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
        }
    }

    // ---- Models endpoint ----

    @Test
    fun `models list endpoint sets anthropic api type and operation name`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MODELS_LIST_RESPONSE))

            client.models().list()

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("models.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `models list response sets count and pagination attributes`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = createClient(server.url("/").toString())
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(MODELS_LIST_RESPONSE))

            client.models().list()

            val trace = analyzeSpans().first()
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
        }
    }

    // ---- Helpers ----

    private fun basicMessageParams() = MessageCreateParams.builder()
        .addUserMessage("Hello")
        .maxTokens(10L)
        .model(testModel)
        .build()

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"

        private val MESSAGES_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "model": "claude-3-5-haiku-20241022",
                "content": [{"type": "text", "text": "Hello!"}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        private val BATCH_RESPONSE = """
            {
                "id": "msgbatch_013Zva2CMHLNnXjNJKqJ2EwZ",
                "type": "message_batch",
                "processing_status": "in_progress",
                "request_counts": {
                    "processing": 1,
                    "succeeded": 0,
                    "errored": 0,
                    "canceled": 0,
                    "expired": 0
                },
                "created_at": 1705000000
            }
        """.trimIndent()

        private val MODELS_LIST_RESPONSE = """
            {
                "data": [
                    {"id": "claude-3-5-haiku-20241022", "type": "model", "display_name": "Claude 3.5 Haiku", "created_at": 1705000000},
                    {"id": "claude-3-5-sonnet-20241022", "type": "model", "display_name": "Claude 3.5 Sonnet", "created_at": 1705000000}
                ],
                "has_more": false,
                "first_id": "claude-3-5-haiku-20241022",
                "last_id": "claude-3-5-sonnet-20241022"
            }
        """.trimIndent()
    }
}
