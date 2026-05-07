/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

private fun makeRequest(): TracyHttpRequest = object : TracyHttpRequest {
    override val body = TracyHttpRequestBody.Json(buildJsonObject { put("model", "test-model") })
    override val contentType = TracyContentType.Application.Json
    override val method = "POST"
    override val url = object : TracyHttpUrl {
        override val scheme = "https"
        override val host = "api.example.com"
        override val pathSegments = listOf("v1", "chat", "completions")
        override val parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    }
}

private fun makeResponse(code: Int): TracyHttpResponse = object : TracyHttpResponse {
    override val code = code
    override val contentType = TracyContentType.Application.Json
    override val body = TracyHttpResponseBody.Json(
        buildJsonObject { put("object", "chat.completion") } as JsonElement
    )
    override val url = makeRequest().url
    override val requestMethod = "POST"
    override fun isError() = code >= 400
}

internal class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    private val adapter = TestLLMTracingAdapter()

    @Test
    fun `registerRequest sets gen_ai provider name attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerRequest(span, makeRequest())
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

    @Test
    fun `registerResponse sets http response status code attribute`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        adapter.registerResponse(span, makeResponse(200))
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
        adapter.registerResponse(span, makeResponse(201))
        span.end()

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes

        assertEquals(201L, attrs[AttributeKey.longKey("http.status_code")], "legacy http.status_code should still be present")
        assertEquals(201L, attrs[AttributeKey.longKey("http.response.status_code")], "http.response.status_code should be present")
    }
}
