/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    private class TestAdapter : LLMTracingAdapter("test-system") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
        override fun getSpanName(request: TracyHttpRequest) = "test-span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
    }

    private fun createRequest(scheme: String, host: String, port: Int): TracyHttpRequest {
        val emptyParams = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        }
        val url = TracyHttpUrlImpl(
            scheme = scheme,
            host = host,
            port = port,
            pathSegments = emptyList(),
            parameters = emptyParams,
        )
        return TracyHttpRequestBody.Empty.asRequestView(
            contentType = null,
            url = url,
            method = "POST",
        )
    }

    @Test
    fun `registerRequest sets server address attribute`() {
        val adapter = TestAdapter()
        val request = createRequest("https", "api.openai.com", 443)

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, request)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(
            "api.openai.com",
            spans.first().attributes[AttributeKey.stringKey("server.address")]
        )
    }

    @Test
    fun `registerRequest sets server port from explicit port`() {
        val adapter = TestAdapter()
        val request = createRequest("https", "api.openai.com", 443)

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, request)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(
            443L,
            spans.first().attributes[AttributeKey.longKey("server.port")]
        )
    }

    @Test
    fun `registerRequest falls back to 443 for https when port is 0`() {
        val adapter = TestAdapter()
        val request = createRequest("https", "api.anthropic.com", 0)

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, request)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(
            443L,
            spans.first().attributes[AttributeKey.longKey("server.port")]
        )
    }

    @Test
    fun `registerRequest falls back to 80 for http when port is 0`() {
        val adapter = TestAdapter()
        val request = createRequest("http", "localhost", 0)

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, request)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(
            80L,
            spans.first().attributes[AttributeKey.longKey("server.port")]
        )
    }

    @Test
    fun `registerRequest sets custom port correctly`() {
        val adapter = TestAdapter()
        val request = createRequest("http", "localhost", 8080)

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, request)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(
            8080L,
            spans.first().attributes[AttributeKey.longKey("server.port")]
        )
    }
}
