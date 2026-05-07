/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TEST_SYSTEM = "test-provider"
private const val TEST_HOST = "api.example.com"
private const val TEST_PORT = 443

private object TestLLMTracingAdapter : LLMTracingAdapter(TEST_SYSTEM) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun getSpanName(request: TracyHttpRequest) = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
}

private object ThrowingLLMTracingAdapter : LLMTracingAdapter(TEST_SYSTEM) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        throw RuntimeException("body parsing failed")
    }
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun getSpanName(request: TracyHttpRequest) = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
}

private fun makeTestRequest(host: String = TEST_HOST, port: Int = TEST_PORT): TracyHttpRequest {
    val url = TracyHttpUrlImpl(
        scheme = "https",
        host = host,
        port = port,
        pathSegments = listOf("v1", "chat", "completions"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    )
    val body = TracyHttpRequestBody.Json(
        JsonObject(mapOf("model" to JsonPrimitive("gpt-4o")))
    )
    return body.asRequestView(contentType = null, url = url, method = "POST")
}

private fun makeTestResponse(code: Int = 200): TracyHttpResponse {
    val url = TracyHttpUrlImpl(
        scheme = "https",
        host = TEST_HOST,
        port = TEST_PORT,
        pathSegments = listOf("v1", "chat", "completions"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    )
    val body = TracyHttpResponseBody.Json(JsonObject(emptyMap()))
    return object : TracyHttpResponse {
        override val contentType = null
        override val code = code
        override val body = body
        override val url = url
        override val requestMethod = "POST"
        override fun isError() = code >= 400
    }
}

class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    @Test
    fun `registerRequest sets gen_ai_provider_name attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            TestLLMTracingAdapter.registerRequest(span, makeTestRequest())
        }
        span.end()

        val traces = analyzeSpans()
        assertNotNull(traces.firstOrNull())
        assertEquals(
            TEST_SYSTEM,
            traces.first().attributes[AttributeKey.stringKey("gen_ai.provider.name")]
        )
    }

    @Test
    fun `registerRequest sets server_address attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            TestLLMTracingAdapter.registerRequest(span, makeTestRequest())
        }
        span.end()

        val traces = analyzeSpans()
        assertNotNull(traces.firstOrNull())
        assertEquals(
            TEST_HOST,
            traces.first().attributes[AttributeKey.stringKey("server.address")]
        )
    }

    @Test
    fun `registerRequest sets server_port attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            TestLLMTracingAdapter.registerRequest(span, makeTestRequest())
        }
        span.end()

        val traces = analyzeSpans()
        assertNotNull(traces.firstOrNull())
        assertEquals(
            TEST_PORT.toLong(),
            traces.first().attributes[AttributeKey.longKey("server.port")]
        )
    }

    @Test
    fun `registerResponse sets http_response_status_code attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            TestLLMTracingAdapter.registerRequest(span, makeTestRequest())
            TestLLMTracingAdapter.registerResponse(span, makeTestResponse(code = 200))
        }
        span.end()

        val traces = analyzeSpans()
        assertNotNull(traces.firstOrNull())
        assertEquals(
            200L,
            traces.first().attributes[AttributeKey.longKey("http.response.status_code")]
        )
    }

    @Test
    fun `registerResponse sets both http_status_code and http_response_status_code`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            TestLLMTracingAdapter.registerRequest(span, makeTestRequest())
            TestLLMTracingAdapter.registerResponse(span, makeTestResponse(code = 201))
        }
        span.end()

        val traces = analyzeSpans()
        val trace = traces.first()
        assertEquals(201L, trace.attributes[AttributeKey.longKey("http.status_code")])
        assertEquals(201L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
    }

    @Test
    fun `registerRequest sets provider and server attributes even when getRequestBodyAttributes throws`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        span.makeCurrent().use {
            ThrowingLLMTracingAdapter.registerRequest(span, makeTestRequest())
        }
        span.end()

        val traces = analyzeSpans()
        assertNotNull(traces.firstOrNull())
        val attrs = traces.first().attributes
        assertEquals(TEST_SYSTEM, attrs[AttributeKey.stringKey("gen_ai.provider.name")])
        assertEquals(TEST_HOST, attrs[AttributeKey.stringKey("server.address")])
        assertEquals(TEST_PORT.toLong(), attrs[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `TracyHttpUrlImpl port is populated correctly`() {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            port = 8080,
            pathSegments = listOf("v1"),
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String) = null
                override fun queryParameterValues(name: String) = emptyList<String?>()
            }
        )
        assertEquals(8080, url.port)
        assertEquals("api.openai.com", url.host)
    }
}
