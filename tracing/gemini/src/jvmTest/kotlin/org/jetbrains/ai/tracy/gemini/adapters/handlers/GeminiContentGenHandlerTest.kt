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
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [GeminiContentGenHandler] streaming support.
 *
 * Calls [GeminiContentGenHandler.handleStreaming] directly against in-memory spans; no network
 * calls are made and no real API keys are required.
 */
@Tag("gemini")
class GeminiContentGenHandlerTest {

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
            .getTracer("gemini-content-gen-test")
        TracingManager.traceSensitiveContent()
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    private fun captureStreaming(sseEvents: String): Attributes {
        val handler = GeminiContentGenHandler(MediaContentExtractorImpl())
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleStreaming(span, sseEvents)
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    @Test
    fun `streaming extracts response id from last chunk`() {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":"STOP","index":0}]}

            data: {"candidates":[{"content":{"parts":[{"text":" world"}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-abc123","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertEquals("resp-abc123", attrs[AttributeKey.stringKey("gen_ai.response.id")])
    }

    @Test
    fun `streaming extracts response model from last chunk`() {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-xyz","usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"totalTokenCount":8}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertEquals("gemini-2.0-flash", attrs[AttributeKey.stringKey("gen_ai.response.model")])
    }

    @Test
    fun `streaming extracts usage tokens from last chunk`() {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-001","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertEquals(10L, attrs[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        assertEquals(5L, attrs[AttributeKey.longKey("gen_ai.usage.output_tokens")])
    }

    @Test
    fun `streaming accumulates text parts across chunks into completion content`() {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}]}

            data: {"candidates":[{"content":{"parts":[{"text":", world!"}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-002","usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":4,"totalTokenCount":12}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertNotNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
        assertEquals("Hello, world!", attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `streaming sets finish reason from last non-empty value`() {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Answer"}],"role":"model"},"index":0}]}

            data: {"candidates":[{"content":{"parts":[{"text":"."}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-003","usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":2,"totalTokenCount":8}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertEquals("STOP", attrs[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
    }

    @Test
    fun `streaming ignores non-data lines and malformed JSON`() {
        val sse = """
            : keep-alive

            data: not-valid-json

            data: {"candidates":[{"content":{"parts":[{"text":"Ok"}],"role":"model"},"finishReason":"STOP","index":0}],"modelVersion":"gemini-2.0-flash","responseId":"resp-004","usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"totalTokenCount":6}}
        """.trimIndent()
        val attrs = captureStreaming(sse)
        assertEquals("resp-004", attrs[AttributeKey.stringKey("gen_ai.response.id")])
        assertEquals("Ok", attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
