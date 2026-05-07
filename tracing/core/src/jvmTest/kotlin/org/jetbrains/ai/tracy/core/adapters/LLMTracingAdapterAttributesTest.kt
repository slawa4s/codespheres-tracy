/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.JsonPrimitive
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
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LLMTracingAdapterAttributesTest : BaseOpenTelemetryTracingTest() {

    private val genAISystem = "test-provider"

    private val adapter = object : LLMTracingAdapter(genAISystem) {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
        override fun getSpanName(request: TracyHttpRequest) = "test-span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
    }

    private fun makeUrl(host: String, port: Int): TracyHttpUrl {
        val params = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        }
        return TracyHttpUrlImpl(
            scheme = "https",
            host = host,
            port = port,
            pathSegments = listOf("v1", "chat", "completions"),
            parameters = params,
        )
    }

    private fun makeRequest(host: String = "api.example.com", port: Int = 443): TracyHttpRequest {
        val url = makeUrl(host, port)
        val body = TracyHttpRequestBody.Json(buildJsonObject { put("model", "test-model") })
        return object : TracyHttpRequest {
            override val body = body
            override val contentType: TracyContentType? = null
            override val url = url
            override val method = "POST"
        }
    }

    private fun makeResponse(code: Int = 200): TracyHttpResponse {
        val url = makeUrl("api.example.com", 443)
        val body = TracyHttpResponseBody.Json(buildJsonObject { put("id", "resp-1") })
        return object : TracyHttpResponse {
            override val contentType: TracyContentType? = null
            override val code = code
            override val body = body
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = code >= 400
        }
    }

    @Test
    fun `registerRequest sets server address, server port, and gen_ai provider name`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, makeRequest(host = "api.openai.com", port = 443))
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals("api.openai.com", trace.attributes[AttributeKey.stringKey("server.address")])
        assertEquals(443L, trace.attributes[AttributeKey.longKey("server.port")])
        assertEquals(genAISystem, trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
    }

    @Test
    fun `registerRequest uses port from url`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, makeRequest(host = "localhost", port = 8080))
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals("localhost", trace.attributes[AttributeKey.stringKey("server.address")])
        assertEquals(8080L, trace.attributes[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `registerResponse sets http response status code`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, makeResponse(code = 200))
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        // legacy attribute still set
        assertEquals(200L, trace.attributes[AttributeKey.longKey("http.status_code")])
    }

    @Test
    fun `registerResponse sets http response status code for error responses`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, makeResponse(code = 429))
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals(429L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        assertEquals(429L, trace.attributes[AttributeKey.longKey("http.status_code")])
    }

    @Test
    fun `registerResponse sets http status code even when body is not a JSON object`() {
        val url = makeUrl("api.example.com", 443)
        val response = object : TracyHttpResponse {
            override val contentType: TracyContentType? = null
            override val code = 400
            override val body = TracyHttpResponseBody.Json(JsonPrimitive("non-object body"))
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = true
        }
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, response)
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals(400L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        assertEquals(400L, trace.attributes[AttributeKey.longKey("http.status_code")])
        assertEquals(StatusCode.ERROR, trace.status.statusCode)
    }

    @Test
    fun `registerResponse sets error_type and gen_ai_error_type from error body`() {
        val url = makeUrl("api.example.com", 443)
        val errorBody = buildJsonObject {
            put("error", buildJsonObject {
                put("type", "invalid_request_error")
                put("message", "Bad request")
            })
        }
        val response = object : TracyHttpResponse {
            override val contentType: TracyContentType? = null
            override val code = 400
            override val body = TracyHttpResponseBody.Json(errorBody)
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = true
        }
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, response)
        span.end()

        val traces = analyzeSpans()
        val trace = traces.firstOrNull()
        assertNotNull(trace)

        assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
        assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("gen_ai.error.type")])
        assertEquals("Bad request", trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
        assertEquals(400L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        assertEquals(StatusCode.ERROR, trace.status.statusCode)
    }
}
