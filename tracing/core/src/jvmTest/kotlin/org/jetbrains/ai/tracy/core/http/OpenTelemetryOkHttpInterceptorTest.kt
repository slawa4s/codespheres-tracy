/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.http

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
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
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpenTelemetryOkHttpInterceptorTest : BaseOpenTelemetryTracingTest() {

    @Test
    fun `http request method is recorded as span attribute`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val client = instrument(OkHttpClient(), MinimalLLMAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/chat/completions"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            assertEquals("POST", spans.first().attributes[AttributeKey.stringKey("http.request.method")])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `full url is recorded as span attribute`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val client = instrument(OkHttpClient(), MinimalLLMAdapter())
            val targetUrl = server.url("/v1/chat/completions")
            val request = Request.Builder()
                .url(targetUrl)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            assertEquals(
                targetUrl.toString(),
                spans.first().attributes[AttributeKey.stringKey("url.full")]
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http request method reflects the actual http verb`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val client = instrument(OkHttpClient(), MinimalLLMAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/models"))
                .get()
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            assertEquals("GET", spans.first().attributes[AttributeKey.stringKey("http.request.method")])
        } finally {
            server.shutdown()
        }
    }
}

private class MinimalLLMAdapter : LLMTracingAdapter("test-system") {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
    override fun getSpanName(request: TracyHttpRequest): String = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest): Boolean = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
}
