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
import org.jetbrains.ai.tracy.core.http.parsers.FormData
import org.jetbrains.ai.tracy.core.http.parsers.FormPart
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
 * Unit tests for [AudioTranscriptionOpenAIApiEndpointHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("openai")
class AudioTranscriptionOpenAIApiEndpointHandlerTest {

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
            .getTracer("audio-transcription-test")
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
        pathSegments = listOf("v1", "audio", "transcriptions"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String>()
        }
    )

    private fun textPart(name: String, value: String): FormPart = FormPart(
        name = name,
        content = value.toByteArray(Charsets.UTF_8),
    )

    private fun filePart(filename: String, contentType: String, content: ByteArray): FormPart {
        val ct = object : TracyContentType {
            override val type = contentType.substringBefore("/")
            override val subtype = contentType.substringAfter("/")
            override fun asString() = contentType
            override fun parameter(name: String) = null
            override fun charset() = null
        }
        return FormPart(
            name = "file",
            filename = filename,
            contentType = ct,
            content = content,
        )
    }

    private fun formRequest(vararg parts: FormPart): TracyHttpRequest {
        val body = TracyHttpRequestBody.FormData(FormData(parts.toList()))
        return body.asRequestView(null, url(), "POST")
    }

    private fun jsonResponse(jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@AudioTranscriptionOpenAIApiEndpointHandlerTest.url()
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun captureRequest(vararg parts: FormPart): Attributes {
        val handler = AudioTranscriptionOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, formRequest(*parts))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(jsonBody: String): Attributes {
        val handler = AudioTranscriptionOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attribute tests ──────────────────────────────────────────────

    @Test
    fun `always sets operation name and api type`() {
        val attrs = captureRequest()
        assertEquals("audio.transcription", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("audio", attrs[AttributeKey.stringKey("openai.api.type")])
    }

    @Test
    fun `file part sets size_bytes and format from filename extension`() {
        val audioBytes = ByteArray(4096) { it.toByte() }
        val attrs = captureRequest(filePart("recording.mp3", "audio/mpeg", audioBytes))

        assertEquals(4096L, attrs[AttributeKey.longKey("tracy.request.audio.size_bytes")])
        assertEquals("mp3", attrs[AttributeKey.stringKey("tracy.request.audio.format")])
    }

    @Test
    fun `file part falls back to content-type subtype when filename has no extension`() {
        val audioBytes = ByteArray(512)
        val attrs = captureRequest(filePart("recording", "audio/wav", audioBytes))

        assertEquals("wav", attrs[AttributeKey.stringKey("tracy.request.audio.format")])
    }

    @Test
    fun `model part sets gen_ai request model`() {
        val attrs = captureRequest(textPart("model", "whisper-1"))
        assertEquals("whisper-1", attrs[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `response_format verbose_json maps to json output type`() {
        val attrs = captureRequest(textPart("response_format", "verbose_json"))
        assertEquals("verbose_json", attrs[AttributeKey.stringKey("tracy.request.response_format")])
        assertEquals("json", attrs[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `response_format json maps to json output type`() {
        val attrs = captureRequest(textPart("response_format", "json"))
        assertEquals("json", attrs[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `response_format text maps to text output type`() {
        val attrs = captureRequest(textPart("response_format", "text"))
        assertEquals("text", attrs[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `multiple timestamp granularities parts are joined and set`() {
        val attrs = captureRequest(
            textPart("timestamp_granularities[]", "word"),
            textPart("timestamp_granularities[]", "segment"),
        )
        assertEquals("word,segment", attrs[AttributeKey.stringKey("tracy.request.timestamp_granularities")])
    }

    @Test
    fun `single timestamp granularity is set correctly`() {
        val attrs = captureRequest(textPart("timestamp_granularities[]", "word"))
        assertEquals("word", attrs[AttributeKey.stringKey("tracy.request.timestamp_granularities")])
    }

    // ── Response attribute tests ──────────────────────────────────────────────

    @Test
    fun `verbose_json response sets language and duration`() {
        val attrs = captureResponse(
            """{"task":"transcribe","language":"english","duration":12.34,"text":"Hello world","words":[{"word":"Hello","start":0.0,"end":0.5},{"word":"world","start":0.6,"end":1.0}]}"""
        )
        assertEquals("english", attrs[AttributeKey.stringKey("tracy.response.transcription.language")])
        assertEquals(12.34, attrs[AttributeKey.doubleKey("tracy.response.transcription.duration_seconds")])
        assertEquals(2L, attrs[AttributeKey.longKey("tracy.response.transcription.words.count")])
    }

    @Test
    fun `response without words array does not set words count`() {
        val attrs = captureResponse(
            """{"task":"transcribe","language":"french","duration":5.0,"text":"Bonjour"}"""
        )
        assertEquals("french", attrs[AttributeKey.stringKey("tracy.response.transcription.language")])
        assertNull(attrs[AttributeKey.longKey("tracy.response.transcription.words.count")])
    }

    @Test
    fun `handleStreaming is a no-op`() {
        val handler = AudioTranscriptionOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        // Should not throw and should set no attributes
        handler.handleStreaming(span, "data: {}")
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
