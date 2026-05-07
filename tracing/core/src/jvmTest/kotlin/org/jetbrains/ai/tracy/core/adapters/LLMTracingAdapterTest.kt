/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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

private const val TEST_PROVIDER = "test-provider"

private object TestLLMTracingAdapter : LLMTracingAdapter(TEST_PROVIDER) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun getSpanName(request: TracyHttpRequest) = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
}

private val testUrl = TracyHttpUrlImpl(
    scheme = "https",
    host = "api.example.com",
    port = 443,
    pathSegments = listOf("v1", "chat", "completions"),
    parameters = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    },
)

private val testRequest = object : TracyHttpRequest {
    override val body = TracyHttpRequestBody.Json(JsonObject(emptyMap()))
    override val contentType = null
    override val url: TracyHttpUrl = testUrl
    override val method = "POST"
}

class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    private val adapter = object : LLMTracingAdapter("test-system") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
        override fun getSpanName(request: TracyHttpRequest) = "test-span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
    }

    private val adapterTestUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.test.com",
        port = 443,
        pathSegments = listOf("v1", "chat"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    )

    @Test
    fun `registerRequest sets gen_ai provider name attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        TestLLMTracingAdapter.registerRequest(span, testRequest)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val spanData = spans.first()

        val providerName = spanData.attributes[AttributeKey.stringKey("gen_ai.provider.name")]
        assertNotNull(providerName, "gen_ai.provider.name attribute should be set")
        assertEquals(TEST_PROVIDER, providerName)
    }

    @Test
    fun `registerRequest gen_ai provider name matches gen_ai system`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        TestLLMTracingAdapter.registerRequest(span, testRequest)
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val spanData = spans.first()

        val system = spanData.attributes[AttributeKey.stringKey("gen_ai.system")]
        val providerName = spanData.attributes[AttributeKey.stringKey("gen_ai.provider.name")]

        assertNotNull(system)
        assertNotNull(providerName)
        assertEquals(system, providerName, "gen_ai.provider.name should equal gen_ai.system")
    }

    @Test
    fun `registerResponse sets http response status code alongside legacy attribute for 200`() {
        val span = TracingManager.tracer.spanBuilder("test-200").startSpan()

        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(buildJsonObject {})
            override val url = adapterTestUrl
            override val requestMethod = "POST"
            override fun isError() = false
        }

        adapter.registerResponse(span, response)
        span.end()

        val traces = analyzeSpans()
        val trace = traces.single { it.name == "test-200" }

        assertEquals(200L, trace.attributes[AttributeKey.longKey("http.status_code")])
        assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
    }

    @Test
    fun `registerResponse sets http response status code alongside legacy attribute for error responses`() {
        val span = TracingManager.tracer.spanBuilder("test-400").startSpan()

        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 400
            override val body = TracyHttpResponseBody.Json(buildJsonObject {})
            override val url = adapterTestUrl
            override val requestMethod = "POST"
            override fun isError() = true
        }

        adapter.registerResponse(span, response)
        span.end()

        val traces = analyzeSpans()
        val trace = traces.single { it.name == "test-400" }

        assertEquals(400L, trace.attributes[AttributeKey.longKey("http.status_code")])
        assertEquals(400L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        assertEquals(StatusCode.ERROR, trace.status.statusCode)
    }
}
