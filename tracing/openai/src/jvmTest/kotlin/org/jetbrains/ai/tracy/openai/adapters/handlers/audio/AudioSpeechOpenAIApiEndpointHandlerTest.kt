/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.audio

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
 * Unit tests for [AudioSpeechOpenAIApiEndpointHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("openai")
class AudioSpeechOpenAIApiEndpointHandlerTest {

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
            .getTracer("audio-speech-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun url(): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.openai.com",
        port = 443,
        pathSegments = listOf("v1", "audio", "speech"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String>()
        }
    )

    private fun jsonRequest(jsonBody: String): TracyHttpRequest {
        val elem = Json.parseToJsonElement(jsonBody)
        val body = TracyHttpRequestBody.Json(elem)
        return body.asRequestView(TracyContentType.Application.Json, url(), "POST")
    }

    private fun jsonResponse(jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@AudioSpeechOpenAIApiEndpointHandlerTest.url()
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun captureRequest(jsonBody: String): Attributes {
        val handler = AudioSpeechOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, jsonRequest(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(jsonBody: String): Attributes {
        val handler = AudioSpeechOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attribute tests ──────────────────────────────────────────────

    @Test
    fun `always sets operation name, api type, and output type`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hello","voice":"alloy"}""")
        assertEquals("audio.speech", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("audio", attrs[AttributeKey.stringKey("openai.api.type")])
        assertEquals("speech", attrs[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `voice is set without JSON quotes`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hello","voice":"alloy"}""")
        assertEquals("alloy", attrs[AttributeKey.stringKey("tracy.request.voice")])
    }

    @Test
    fun `model sets gen_ai request model`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hello","voice":"alloy"}""")
        assertEquals("tts-1", attrs[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `response_format is extracted from request body`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hi","voice":"nova","response_format":"mp3"}""")
        assertEquals("mp3", attrs[AttributeKey.stringKey("tracy.request.response_format")])
    }

    @Test
    fun `speed is extracted as a double`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hi","voice":"echo","speed":1.25}""")
        assertEquals(1.25, attrs[AttributeKey.doubleKey("tracy.request.speed")])
    }

    @Test
    fun `missing optional fields do not set attributes`() {
        val attrs = captureRequest("""{"model":"tts-1","input":"Hi","voice":"alloy"}""")
        assertNull(attrs[AttributeKey.stringKey("tracy.request.response_format")])
        assertNull(attrs[AttributeKey.doubleKey("tracy.request.speed")])
    }

    // ── Response attribute tests ──────────────────────────────────────────────

    @Test
    fun `response with _tracy_binary_size_bytes sets audio size attribute`() {
        val attrs = captureResponse("""{"_tracy_binary_size_bytes":98304}""")
        assertEquals(98304L, attrs[AttributeKey.longKey("tracy.response.audio.size_bytes")])
    }

    @Test
    fun `response without _tracy_binary_size_bytes does not set audio size`() {
        val attrs = captureResponse("{}")
        assertNull(attrs[AttributeKey.longKey("tracy.response.audio.size_bytes")])
    }

    @Test
    fun `handleStreaming is a no-op`() {
        val handler = AudioSpeechOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleStreaming(span, "data: {}")
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
