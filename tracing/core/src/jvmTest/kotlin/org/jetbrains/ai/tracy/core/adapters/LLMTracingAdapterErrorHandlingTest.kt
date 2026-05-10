/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [LLMTracingAdapter.registerResponse] edge-case behaviour.
 *
 * Exercises the code path where the HTTP response body cannot be decoded as a
 * [kotlinx.serialization.json.JsonObject] (e.g. a JSON primitive or empty body).
 * Prior to the fix, a non-JsonObject body caused a non-local return out of
 * `registerResponse`, silently skipping `error.type` and the span status.
 */
class LLMTracingAdapterErrorHandlingTest : BaseOpenTelemetryTracingTest() {

    private val adapter = object : LLMTracingAdapter("test") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
        override fun getSpanName(request: TracyHttpRequest) = "test.span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit
    }

    @Test
    fun `errorTypeIsSetWhenResponseBodyIsNotAJsonObject`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val response = object : TracyHttpResponse {
            override val contentType: TracyContentType? = null
            override val code: Int = 422
            override val body: TracyHttpResponseBody =
                TracyHttpResponseBody.Json(JsonPrimitive("unprocessable"))
            override val url: TracyHttpUrl = TracyHttpUrlImpl(
                scheme = "https",
                host = "api.example.com",
                port = 443,
                pathSegments = emptyList(),
                parameters = object : TracyQueryParameters {
                    override fun queryParameter(name: String): String? = null
                    override fun queryParameterValues(name: String): List<String?> = emptyList()
                }
            )
            override val requestMethod: String = "POST"
            override fun isError(): Boolean = code >= 400
        }

        adapter.registerResponse(span, response)
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single()

        assertEquals("422", spanData.attributes[AttributeKey.stringKey("error.type")])
        assertEquals(StatusCode.ERROR, spanData.status.statusCode)
    }
}
