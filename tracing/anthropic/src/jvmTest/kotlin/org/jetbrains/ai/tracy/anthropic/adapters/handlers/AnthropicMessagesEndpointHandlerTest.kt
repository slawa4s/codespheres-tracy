/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AnthropicMessagesEndpointHandler].
 *
 * All assertions are made against attributes recorded on in-memory spans; no network calls
 * are made and no real API keys are required.
 */
@Tag("anthropic")
class AnthropicMessagesEndpointHandlerTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracer: Tracer

    @BeforeEach
    fun setup() {
        spanExporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        tracer = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build()
            .getTracer("anthropic-messages-test")
        // Enable content capture so assertions on message/tool content are not "REDACTED"
        TracingManager.traceSensitiveContent()
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun url(path: String): TracyHttpUrl {
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        return TracyHttpUrlImpl(
            scheme = "https",
            host = "api.anthropic.com",
            port = 443,
            pathSegments = segments,
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String) = null
                override fun queryParameterValues(name: String) = emptyList<String>()
            }
        )
    }

    private fun request(path: String, jsonBody: String): TracyHttpRequest {
        val elem = Json.parseToJsonElement(jsonBody)
        return TracyHttpRequestBody.Json(elem).asRequestView(
            contentType = TracyContentType.Application.Json,
            url = url(path),
            method = "POST"
        )
    }

    private fun response(path: String, jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@AnthropicMessagesEndpointHandlerTest.url(path)
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun capture(requestJson: String, responseJson: String): Attributes {
        val handler = AnthropicMessagesEndpointHandler(MediaContentExtractorImpl())
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request("/v1/messages", requestJson))
        handler.handleResponseAttributes(span, response("/v1/messages", responseJson))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attributes ────────────────────────────────────────────────────

    @Test
    fun `extracts model from request`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[{"type":"text","text":"Hi!"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
        )
        assertEquals("claude-3-5-haiku-latest", attrs[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `extracts max_tokens from request`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":512,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        assertEquals(512L, attrs[AttributeKey.longKey("gen_ai.request.max_tokens")])
    }

    @Test
    fun `extracts temperature from request`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"temperature":0.7,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        assertEquals(0.7, attrs[AttributeKey.doubleKey("gen_ai.request.temperature")])
    }

    @Test
    fun `extracts prompt messages from request`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Say hi"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        assertEquals("user", attrs[AttributeKey.stringKey("gen_ai.prompt.0.role")])
        assertNotNull(attrs[AttributeKey.stringKey("gen_ai.prompt.0.content")])
    }

    @Test
    fun `extracts tool definitions from request`() {
        val attrs = capture(
            requestJson = """{
                "model":"claude-3-5-haiku-latest",
                "max_tokens":100,
                "messages":[{"role":"user","content":"Call greet"}],
                "tools":[{"name":"greet","type":"custom","description":"Greet the user","input_schema":{"type":"object","properties":{}}}]
            }""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        assertEquals("greet", attrs[AttributeKey.stringKey("gen_ai.tool.0.name")])
        assertEquals("custom", attrs[AttributeKey.stringKey("gen_ai.tool.0.type")])
    }

    // ── Response attributes ───────────────────────────────────────────────────

    @Test
    fun `extracts response id`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_abc123","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[{"type":"text","text":"Hi!"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
        )
        assertEquals("msg_abc123", attrs[AttributeKey.stringKey("gen_ai.response.id")])
    }

    @Test
    fun `extracts response model`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-20241022","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        assertEquals("claude-3-5-haiku-20241022", attrs[AttributeKey.stringKey("gen_ai.response.model")])
    }

    @Test
    fun `extracts usage tokens from response`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[{"type":"text","text":"Hi!"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}"""
        )
        assertEquals(10L, attrs[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        assertEquals(5L, attrs[AttributeKey.longKey("gen_ai.usage.output_tokens")])
    }

    @Test
    fun `extracts stop reason from response`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"max_tokens","usage":{"input_tokens":5,"output_tokens":0}}"""
        )
        val finishReasons = attrs[AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")]
        assertNotNull(finishReasons)
        assertTrue(finishReasons.contains("max_tokens"))
    }

    @Test
    fun `extracts text content from response`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[{"type":"text","text":"Hello, world!"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":5}}"""
        )
        assertEquals("text", attrs[AttributeKey.stringKey("gen_ai.completion.0.type")])
        assertNotNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `extracts tool_use content from response`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Call greet"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[{"type":"tool_use","id":"toolu_01","name":"greet","input":{"name":"World"}}],"stop_reason":"tool_use","usage":{"input_tokens":20,"output_tokens":10}}"""
        )
        assertEquals("tool_use", attrs[AttributeKey.stringKey("gen_ai.completion.0.type")])
        assertEquals("toolu_01", attrs[AttributeKey.stringKey("gen_ai.completion.0.tool.call.id")])
        assertEquals("greet", attrs[AttributeKey.stringKey("gen_ai.completion.0.tool.name")])
    }

    @Test
    fun `extracts cache token counts from response`() {
        val attrs = capture(
            requestJson = """{"model":"claude-3-5-haiku-latest","max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""",
            responseJson = """{"id":"msg_01","type":"message","role":"assistant","model":"claude-3-5-haiku-latest","content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":0,"cache_creation_input_tokens":100,"cache_read_input_tokens":50}}"""
        )
        assertEquals(100L, attrs[AttributeKey.longKey("gen_ai.usage.cache_creation.input_tokens")])
        assertEquals(50L, attrs[AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")])
    }
}
