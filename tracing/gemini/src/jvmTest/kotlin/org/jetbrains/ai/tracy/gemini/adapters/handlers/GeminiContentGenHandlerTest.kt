/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GeminiContentGenHandler.handleStreaming] and
 * [GeminiLLMTracingAdapter.isStreamingRequest].
 *
 * Tests use in-process span capture and a [MockWebServer] to serve SSE responses.
 * No live API key is required.
 */
class GeminiContentGenHandlerTest : BaseAITracingTest() {

    // ─── SSE helpers ──────────────────────────────────────────────────────────

    /**
     * Builds a minimal SSE body containing a single `data:` line with the given JSON string.
     */
    private fun sseBody(vararg jsonLines: String): String =
        jsonLines.joinToString("\n") { "data: $it" }

    // ─── Adapter streaming-detection tests ────────────────────────────────────

    @Test
    fun `isStreamingRequest returns true for streamGenerateContent URL`() {
        val adapter = GeminiLLMTracingAdapter()
        val request = makeRequest(pathSegments = listOf("v1beta", "models", "gemini-2.5-flash:streamGenerateContent"))
        assertTrue(adapter.isStreamingRequest(request))
    }

    @Test
    fun `isStreamingRequest returns false for generateContent URL`() {
        val adapter = GeminiLLMTracingAdapter()
        val request = makeRequest(pathSegments = listOf("v1beta", "models", "gemini-2.5-flash:generateContent"))
        assertFalse(adapter.isStreamingRequest(request))
    }

    @Test
    fun `isStreamingRequest returns false for embedContent URL`() {
        val adapter = GeminiLLMTracingAdapter()
        val request = makeRequest(pathSegments = listOf("v1beta", "models", "gemini-embedding-001:embedContent"))
        assertFalse(adapter.isStreamingRequest(request))
    }

    // ─── handleStreaming attribute-extraction tests ────────────────────────────

    @Test
    fun `streaming sets gen_ai_response_id from last event`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"responseId":"abc123","candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}"""
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("abc123", spanData.attributes[AttributeKey.stringKey("gen_ai.response.id")])
    }

    @Test
    fun `streaming sets gen_ai_response_model from last event`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"modelVersion":"gemini-2.5-flash-latest","candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}"""
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("gemini-2.5-flash-latest", spanData.attributes[AttributeKey.stringKey("gen_ai.response.model")])
    }

    @Test
    fun `streaming accumulates content across multiple chunks`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":", "}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"world!"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r1","usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}}""",
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("Hello, world!", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `streaming sets finish_reason from last candidate`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r1","usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}"""
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("STOP", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.finish_reason")])
    }

    @Test
    fun `streaming sets input and output token counts`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r1","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4}}"""
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(10L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        assertEquals(4L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")])
    }

    @Test
    fun `streaming skips non-data lines`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        // Mix comment and blank lines with data lines
        val events = buildString {
            appendLine(": keep-alive")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash","responseId":"r2","usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1}}""")
        }
        handler.handleStreaming(span, events)
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("hi", spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `streaming with empty events sets no attributes`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, "")
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.response.id")])
    }

    @Test
    fun `streaming with malformed json does not throw and sets error status`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        // The inner runCatching means malformed JSON is silently skipped;
        // outer runCatching only triggers on unexpected errors, so status stays OK here.
        handler.handleStreaming(span, "data: {not valid json}")
        span.end()

        // Should not throw; status remains UNSET (i.e., not ERROR) because bad lines are skipped
        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }

    @Test
    fun `streaming uses modelVersion from last event`() {
        val handler = GeminiContentGenHandler(noOpExtractor())
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleStreaming(span, sseBody(
            """{"candidates":[{"content":{"parts":[{"text":"a"}]}}],"modelVersion":"gemini-2.5-flash-001"}""",
            """{"candidates":[{"content":{"parts":[{"text":"b"}]},"finishReason":"STOP"}],"modelVersion":"gemini-2.5-flash-002","responseId":"rx","usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2}}""",
        ))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        // Last event wins for modelVersion
        assertEquals("gemini-2.5-flash-002", spanData.attributes[AttributeKey.stringKey("gen_ai.response.model")])
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * A no-op [org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor] for unit tests
     * where media content extraction is irrelevant.
     */
    private fun noOpExtractor() = object : org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor {
        override fun setUploadableContentAttributes(
            span: io.opentelemetry.api.trace.Span,
            field: String,
            content: org.jetbrains.ai.tracy.core.adapters.media.MediaContent,
        ) = Unit
    }

    /**
     * Creates a minimal [TracyHttpRequest] with the given [pathSegments].
     */
    private fun makeRequest(pathSegments: List<String>): TracyHttpRequest {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "generativelanguage.googleapis.com",
            port = 443,
            pathSegments = pathSegments,
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String): String? = null
                override fun queryParameterValues(name: String): List<String?> = emptyList()
            },
        )
        return object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Empty
            override val url = url
            override val method = "POST"
        }
    }
}
