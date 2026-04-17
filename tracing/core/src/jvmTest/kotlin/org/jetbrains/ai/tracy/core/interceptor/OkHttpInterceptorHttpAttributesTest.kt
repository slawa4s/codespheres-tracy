/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.interceptor

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
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OkHttpInterceptorHttpAttributesTest : BaseAITracingTest() {

    private val testAdapter = object : LLMTracingAdapter("test-system") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
        override fun getSpanName(request: TracyHttpRequest) = "test-span"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
    }

    @Test
    fun `intercept sets http_request_method attribute`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val client = instrument(OkHttpClient(), testAdapter)
            val request = Request.Builder()
                .url(server.url("/v1/chat/completions"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)

            val method = spans.first().attributes[AttributeKey.stringKey("http.request.method")]
            assertEquals("POST", method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `intercept sets url_full attribute with host and path`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val path = "/v1/chat/completions"
            val serverUrl = server.url(path)

            val client = instrument(OkHttpClient(), testAdapter)
            val request = Request.Builder()
                .url(serverUrl)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)

            val urlFull = spans.first().attributes[AttributeKey.stringKey("url.full")]
            assertNotNull(urlFull)
            // url.full must include scheme, host, port (if non-default), and path
            assertEquals(serverUrl.toString(), urlFull)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `intercept sets http_request_method for GET request`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .addHeader("Content-Type", "application/json")
            )

            val client = instrument(OkHttpClient(), testAdapter)
            val request = Request.Builder()
                .url(server.url("/v1/models"))
                .get()
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)

            val method = spans.first().attributes[AttributeKey.stringKey("http.request.method")]
            assertEquals("GET", method)
        } finally {
            server.shutdown()
        }
    }
}
