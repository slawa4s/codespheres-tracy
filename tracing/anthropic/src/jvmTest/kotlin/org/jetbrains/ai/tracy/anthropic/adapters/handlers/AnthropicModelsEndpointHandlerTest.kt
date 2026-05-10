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
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [AnthropicModelsEndpointHandler].
 *
 * All assertions target in-memory spans; no network calls are made and no real API keys
 * are required.
 */
@Tag("anthropic")
class AnthropicModelsEndpointHandlerTest {

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
            .getTracer("models-test")
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
                override fun queryParameterValues(name: String) = emptyList<String?>()
            }
        )
    }

    private fun request(path: String, method: String = "GET"): TracyHttpRequest =
        TracyHttpRequestBody.Empty.asRequestView(TracyContentType.Application.Json, url(path), method)

    private fun response(path: String, jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@AnthropicModelsEndpointHandlerTest.url(path)
            override val requestMethod = "GET"
            override fun isError() = false
        }
    }

    private fun capture(path: String, responseJson: String = "{}"): Attributes {
        val handler = AnthropicModelsEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request(path))
        handler.handleResponseAttributes(span, response(path, responseJson))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── gen_ai.operation.name ─────────────────────────────────────────────────

    @Test
    fun `models retrieve sets operation name`() {
        val attrs = capture("/v1/models/claude-3-5-haiku-20241022")
        assertEquals("models.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `models list sets operation name`() {
        val attrs = capture("/v1/models")
        assertEquals("models.list", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `retrieve and list produce distinct operation names`() {
        val ops = mutableSetOf<String>()
        listOf("/v1/models", "/v1/models/claude-3-5-haiku-20241022").forEach { path ->
            ops.add(capture(path)[AttributeKey.stringKey("gen_ai.operation.name")]!!)
            spanExporter.reset()
        }
        assertEquals(2, ops.size, "Both models routes must produce distinct operation names: $ops")
    }

    // ── anthropic.api.type ────────────────────────────────────────────────────

    @Test
    fun `every models route sets anthropic api type to models`() {
        listOf("/v1/models", "/v1/models/claude-3-5-haiku-20241022").forEach { path ->
            val attrs = capture(path)
            assertEquals(
                "models", attrs[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type must be 'models' for GET $path"
            )
            spanExporter.reset()
        }
    }

    // ── gen_ai.request.model ──────────────────────────────────────────────────

    @Test
    fun `models retrieve sets gen_ai request model from URL`() {
        val attrs = capture("/v1/models/claude-3-5-haiku-20241022")
        assertEquals("claude-3-5-haiku-20241022", attrs[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `models list does not set gen_ai request model`() {
        val attrs = capture("/v1/models")
        assertEquals(null, attrs[AttributeKey.stringKey("gen_ai.request.model")])
    }

    // ── gen_ai.response.model.* ───────────────────────────────────────────────

    @Test
    fun `response attributes are parsed from model object`() {
        val responseBody = """
            {
              "type": "model",
              "id": "claude-3-5-haiku-20241022",
              "display_name": "Claude 3.5 Haiku",
              "created_at": "2024-10-22T00:00:00Z",
              "context_window": 200000,
              "max_output_tokens": 8192,
              "capabilities": {
                "image_input": { "supported": true },
                "batch_processing": { "supported": true },
                "citations": { "supported": false }
              }
            }
        """.trimIndent()
        val attrs = capture("/v1/models/claude-3-5-haiku-20241022", responseJson = responseBody)

        assertEquals("claude-3-5-haiku-20241022", attrs[AttributeKey.stringKey("gen_ai.response.model")])
        assertEquals("claude-3-5-haiku-20241022", attrs[AttributeKey.stringKey("gen_ai.response.model.id")])
        assertEquals("Claude 3.5 Haiku", attrs[AttributeKey.stringKey("gen_ai.response.model.display_name")])
        assertEquals("2024-10-22T00:00:00Z", attrs[AttributeKey.stringKey("gen_ai.response.model.created_at")])
        assertEquals(200000L, attrs[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")])
        assertEquals(8192L, attrs[AttributeKey.longKey("gen_ai.response.model.max_output_tokens")])
        assertEquals(true, attrs[AttributeKey.booleanKey("gen_ai.response.model.capabilities.vision")])
        assertEquals(true, attrs[AttributeKey.booleanKey("gen_ai.response.model.capabilities.batch")])
        assertEquals(false, attrs[AttributeKey.booleanKey("gen_ai.response.model.capabilities.citations")])
    }

    @Test
    fun `response with missing optional fields does not throw`() {
        val attrs = capture("/v1/models/claude-3-opus-latest", responseJson = """{"id": "claude-3-opus-latest"}""")
        assertEquals("claude-3-opus-latest", attrs[AttributeKey.stringKey("gen_ai.response.model")])
        assertEquals(null, attrs[AttributeKey.stringKey("gen_ai.response.model.display_name")])
        assertEquals(null, attrs[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")])
        assertEquals(null, attrs[AttributeKey.booleanKey("gen_ai.response.model.capabilities.vision")])
    }
}
