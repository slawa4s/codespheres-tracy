/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiEmbedHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("gemini")
class GeminiEmbedHandlerTest {

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
            .getTracer("gemini-embed-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun embedContentUrl(): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "models", "text-embedding-004:embedContent"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    )

    private fun jsonRequest(jsonBody: String, url: TracyHttpUrl = embedContentUrl()): TracyHttpRequest {
        val elem = Json.parseToJsonElement(jsonBody)
        val body = TracyHttpRequestBody.Json(elem)
        return body.asRequestView(TracyContentType.Application.Json, url, "POST")
    }

    private fun jsonResponse(jsonBody: String, url: TracyHttpUrl = embedContentUrl()): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun captureRequest(jsonBody: String, url: TracyHttpUrl = embedContentUrl()): Attributes {
        val handler = GeminiEmbedHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, jsonRequest(jsonBody, url))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(jsonBody: String, url: TracyHttpUrl = embedContentUrl()): Attributes {
        val handler = GeminiEmbedHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(jsonBody, url))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attributes ────────────────────────────────────────────────────

    @Test
    fun `always sets operation name to embedContent`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}}""")
        assertEquals("embedContent", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `always sets output type to embedding`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}}""")
        assertEquals("embedding", attrs[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `taskType is extracted into gen_ai request task_type`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}, "taskType": "RETRIEVAL_DOCUMENT"}""")
        assertEquals("RETRIEVAL_DOCUMENT", attrs[AttributeKey.stringKey("gen_ai.request.task_type")])
    }

    @Test
    fun `outputDimensionality is extracted into gen_ai request output_dimensionality`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}, "outputDimensionality": 256}""")
        assertEquals(256L, attrs[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }

    @Test
    fun `missing taskType does not set gen_ai request task_type`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}}""")
        assertNull(attrs[AttributeKey.stringKey("gen_ai.request.task_type")])
    }

    @Test
    fun `missing outputDimensionality does not set gen_ai request output_dimensionality`() {
        val attrs = captureRequest("""{"content": {"parts": [{"text": "Hello"}]}}""")
        assertNull(attrs[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }

    // ── Response attributes ───────────────────────────────────────────────────

    @Test
    fun `embedding values size is extracted as gen_ai response embedding dimension`() {
        val response = """{"embedding": {"values": [0.1, 0.2, 0.3, 0.4]}}"""
        val attrs = captureResponse(response)
        assertEquals(4L, attrs[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
    }

    @Test
    fun `missing embedding in response does not throw`() {
        val attrs = captureResponse("{}")
        assertNull(attrs[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
    }

    @Test
    fun `full request with task type and dimensionality sets all attributes`() {
        val attrs = captureRequest(
            """{"content": {"parts": [{"text": "doc"}]}, "taskType": "SEMANTIC_SIMILARITY", "outputDimensionality": 512}"""
        )
        assertEquals("embedContent", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("embedding", attrs[AttributeKey.stringKey("gen_ai.output.type")])
        assertEquals("SEMANTIC_SIMILARITY", attrs[AttributeKey.stringKey("gen_ai.request.task_type")])
        assertEquals(512L, attrs[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }
}
