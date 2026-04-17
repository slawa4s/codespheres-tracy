/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpenTelemetryOkHttpInterceptorTest : BaseOpenTelemetryTracingTest() {

    private val testAdapter = object : LLMTracingAdapter("test-system") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
        override fun getSpanName(request: TracyHttpRequest) = "test-span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
    }

    @Test
    fun `http_request_method and url_full attributes are set on POST request`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{}""").addHeader("Content-Type", "application/json"))

        try {
            val client = instrument(OkHttpClient(), testAdapter)
            val url = server.url("/v1/chat/completions")

            val request = Request.Builder()
                .url(url)
                .post("""{"model":"test"}""".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { }

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            val span = spans.first()

            assertEquals("POST", span.attributes[AttributeKey.stringKey("http.request.method")])
            assertEquals(url.toString(), span.attributes[AttributeKey.stringKey("url.full")])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http_request_method and url_full attributes are set on GET request`() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{}""").addHeader("Content-Type", "application/json"))

        try {
            val client = instrument(OkHttpClient(), testAdapter)
            val url = server.url("/v1/models")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { }

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            val span = spans.first()

            assertEquals("GET", span.attributes[AttributeKey.stringKey("http.request.method")])
            assertEquals(url.toString(), span.attributes[AttributeKey.stringKey("url.full")])
        } finally {
            server.shutdown()
        }
    }
}
