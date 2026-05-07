/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TEST_PROVIDER = "test-provider"

private class TestLLMTracingAdapter : LLMTracingAdapter(TEST_PROVIDER) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun getSpanName(request: TracyHttpRequest) = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
}

private val emptyParams = object : TracyQueryParameters {
    override fun queryParameter(name: String): String? = null
    override fun queryParameterValues(name: String): List<String?> = emptyList()
}

private fun createRequest(scheme: String, host: String, port: Int): TracyHttpRequest {
    val url = TracyHttpUrlImpl(
        scheme = scheme,
        host = host,
        port = port,
        pathSegments = listOf("v1", "chat", "completions"),
        parameters = emptyParams,
    )
    return TracyHttpRequestBody.Empty.asRequestView(
        contentType = null,
        url = url,
        method = "POST",
    )
}

private fun createResponse(code: Int): TracyHttpResponse = object : TracyHttpResponse {
    override val code = code
    override val contentType = TracyContentType.Application.Json
    override val body = TracyHttpResponseBody.Json(
        buildJsonObject { put("object", "chat.completion") } as JsonElement
    )
    override val url = object : TracyHttpUrl {
        override val scheme = "https"
        override val host = "api.example.com"
        override val pathSegments = listOf("v1", "chat", "completions")
        override val parameters = emptyParams
    }
    override val requestMethod = "POST"
    override fun isError() = code >= 400
}

/** Simulates an error response with no JSON error body (e.g., plain-text or empty body, represented as empty JSON object). */
private fun createErrorResponseEmptyBody(code: Int): TracyHttpResponse = object : TracyHttpResponse {
    override val code = code
    override val contentType = null
    override val body = TracyHttpResponseBody.Json(JsonObject(emptyMap()))
    override val url = object : TracyHttpUrl {
        override val scheme = "https"
        override val host = "api.example.com"
        override val pathSegments = listOf("v1", "messages")
        override val parameters = emptyParams
    }
    override val requestMethod = "POST"
    override fun isError() = code >= 400
}

/** Simulates an error response whose JSON body includes an `error` object with a `type` field. */
private fun createErrorResponseWithErrorType(code: Int, errorType: String): TracyHttpResponse = object : TracyHttpResponse {
    override val code = code
    override val contentType = TracyContentType.Application.Json
    override val body = TracyHttpResponseBody.Json(
        buildJsonObject {
            put("error", buildJsonObject {
                put("type", errorType)
                put("message", "An error occurred")
            })
        } as JsonElement
    )
    override val url = object : TracyHttpUrl {
        override val scheme = "https"
        override val host = "api.example.com"
        override val pathSegments = listOf("v1", "messages")
        override val parameters = emptyParams
    }
    override val requestMethod = "POST"
    override fun isError() = code >= 400
}

internal class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    private val adapter = TestLLMTracingAdapter()

    // ============ server.address / server.port ============

    @Test
    fun `registerRequest sets server address attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("https", "api.openai.com", 443))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals("api.openai.com", spans.first().attributes[AttributeKey.stringKey("server.address")])
    }

    @Test
    fun `registerRequest sets server port from explicit port`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("https", "api.openai.com", 443))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(443L, spans.first().attributes[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `registerRequest falls back to 443 for https when port is 0`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("https", "api.anthropic.com", 0))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(443L, spans.first().attributes[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `registerRequest falls back to 80 for http when port is 0`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("http", "localhost", 0))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(80L, spans.first().attributes[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `registerRequest sets custom port correctly`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("http", "localhost", 8080))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(8080L, spans.first().attributes[AttributeKey.longKey("server.port")])
    }

    // ============ gen_ai.provider.name ============

    @Test
    fun `registerRequest sets gen_ai provider name attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, createRequest("https", "api.example.com", 443))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals(
            TEST_PROVIDER,
            attrs[AttributeKey.stringKey("gen_ai.provider.name")],
            "gen_ai.provider.name should equal the provider name"
        )
        assertEquals(
            TEST_PROVIDER,
            attrs[AttributeKey.stringKey("gen_ai.system")],
            "gen_ai.system should still be set alongside gen_ai.provider.name"
        )
    }

    // ============ http.response.status_code ============

    @Test
    fun `registerResponse sets http response status code attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createResponse(200))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        val statusCode = attrs[AttributeKey.longKey("http.response.status_code")]
        assertNotNull(statusCode, "http.response.status_code should be present")
        assertEquals(200L, statusCode, "http.response.status_code should equal the HTTP status code")
    }

    @Test
    fun `registerResponse sets both http status code attributes`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createResponse(201))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals(201L, attrs[AttributeKey.longKey("http.status_code")], "legacy http.status_code should still be present")
        assertEquals(201L, attrs[AttributeKey.longKey("http.response.status_code")], "http.response.status_code should be present")
    }

    // ============ http.response.status_code for error responses ============

    @Test
    fun `registerResponse sets http response status code for error response`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createResponse(429))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals(429L, attrs[AttributeKey.longKey("http.response.status_code")],
            "http.response.status_code must be set even for error responses")
    }

    @Test
    fun `registerResponse sets http response status code even when body is absent`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createErrorResponseEmptyBody(503))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertNotNull(attrs[AttributeKey.longKey("http.response.status_code")],
            "http.response.status_code must be set even when body has no content")
        assertEquals(503L, attrs[AttributeKey.longKey("http.response.status_code")])
    }

    // ============ error.type ============

    @Test
    fun `registerResponse sets error type from body when present`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createErrorResponseWithErrorType(400, "invalid_request_error"))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals("invalid_request_error", attrs[AttributeKey.stringKey("error.type")],
            "error.type should be taken from error.type field in the response body")
    }

    @Test
    fun `registerResponse falls back to HTTP status code string for error type when body has no error type`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createErrorResponseEmptyBody(429))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals("429", attrs[AttributeKey.stringKey("error.type")],
            "error.type should fall back to the HTTP status code string when body has no error type")
    }

    @Test
    fun `registerResponse sets error type to HTTP status code string for non-JSON error body`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createErrorResponseEmptyBody(503))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals("503", spans.first().attributes[AttributeKey.stringKey("error.type")])
    }

    // ============ span status for error responses ============

    @Test
    fun `registerResponse sets span status to ERROR for error responses`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createErrorResponseEmptyBody(500))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(StatusCode.ERROR, spans.first().status.statusCode,
            "span status must be ERROR for HTTP error responses")
    }

    @Test
    fun `registerResponse sets span status to OK for success responses`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, createResponse(200))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        assertEquals(StatusCode.OK, spans.first().status.statusCode,
            "span status must be OK for HTTP success responses")
    }
}
