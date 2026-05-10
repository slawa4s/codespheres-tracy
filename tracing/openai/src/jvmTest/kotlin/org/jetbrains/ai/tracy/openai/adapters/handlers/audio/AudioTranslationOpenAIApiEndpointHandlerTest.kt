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
import kotlin.test.assertTrue

/**
 * Unit tests for [AudioTranslationOpenAIApiEndpointHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("openai")
class AudioTranslationOpenAIApiEndpointHandlerTest {

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
            .getTracer("audio-translation-test")
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
        pathSegments = listOf("v1", "audio", "translations"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
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
            override val url = this@AudioTranslationOpenAIApiEndpointHandlerTest.url()
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun captureRequest(vararg parts: FormPart): Attributes {
        val handler = AudioTranslationOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, formRequest(*parts))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(jsonBody: String): Attributes {
        val handler = AudioTranslationOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attribute tests ──────────────────────────────────────────────

    @Test
    fun `always sets operation name and api type`() {
        val attrs = captureRequest()
        assertEquals("audio.translation", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("audio", attrs[AttributeKey.stringKey("openai.api.type")])
    }

    @Test
    fun `file part sets size_bytes and format from filename extension`() {
        val audioBytes = ByteArray(8192) { it.toByte() }
        val attrs = captureRequest(filePart("speech.mp3", "audio/mpeg", audioBytes))

        assertEquals(8192L, attrs[AttributeKey.longKey("tracy.request.audio.size_bytes")])
        assertEquals("mp3", attrs[AttributeKey.stringKey("tracy.request.audio.format")])
    }

    @Test
    fun `file part falls back to content-type subtype when filename has no extension`() {
        val audioBytes = ByteArray(256)
        val attrs = captureRequest(filePart("speech", "audio/wav", audioBytes))

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
    fun `temperature part is parsed as double and set`() {
        val attrs = captureRequest(textPart("temperature", "0.7"))
        assertEquals(0.7, attrs[AttributeKey.doubleKey("tracy.request.temperature")])
    }

    @Test
    fun `temperature part with invalid value is ignored`() {
        val attrs = captureRequest(textPart("temperature", "not-a-number"))
        assertNull(attrs[AttributeKey.doubleKey("tracy.request.temperature")])
    }

    @Test
    fun `prompt part sets prompt present flag`() {
        val attrs = captureRequest(textPart("prompt", "Translate to English"))
        assertTrue(attrs[AttributeKey.booleanKey("tracy.request.prompt.present")] == true)
    }

    @Test
    fun `no prompt part does not set prompt present flag`() {
        val attrs = captureRequest(textPart("model", "whisper-1"))
        assertNull(attrs[AttributeKey.booleanKey("tracy.request.prompt.present")])
    }

    // ── Response attribute tests ──────────────────────────────────────────────

    @Test
    fun `response body sets translation duration`() {
        val attrs = captureResponse(
            """{"text":"Hello world","duration":9.87}"""
        )
        assertEquals(9.87, attrs[AttributeKey.doubleKey("tracy.response.translation.duration_seconds")])
    }

    @Test
    fun `response body without duration sets no duration attribute`() {
        val attrs = captureResponse("""{"text":"Hello world"}""")
        assertNull(attrs[AttributeKey.doubleKey("tracy.response.translation.duration_seconds")])
    }

    @Test
    fun `handleStreaming is a no-op`() {
        val handler = AudioTranslationOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleStreaming(span, "data: {}")
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
