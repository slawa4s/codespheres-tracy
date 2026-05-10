/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LLMTracingAdapterTest : BaseAITracingTest() {

    private inner class StubAdapter : LLMTracingAdapter("stub") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
        override fun getSpanName(request: TracyHttpRequest) = "stub.call"
        override fun isStreamingRequest(request: TracyHttpRequest) = false
        override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
    }

    @Test
    fun `http response status code is set even when response body is empty`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400))
        server.start()

        try {
            val client = instrument(OkHttpClient(), StubAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()

            assertEquals(400L, span.attributes[AttributeKey.longKey("http.response.status_code")])
            assertEquals(StatusCode.ERROR, span.status.statusCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `error type is emitted alongside gen_ai error type for JSON error responses`() {
        val errorBody = """{"error":{"type":"invalid_request_error","message":"Bad request","code":"400"}}"""
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody(errorBody)
        )
        server.start()

        try {
            val client = instrument(OkHttpClient(), StubAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()

            assertEquals(400L, span.attributes[AttributeKey.longKey("http.response.status_code")])
            assertEquals("invalid_request_error", span.attributes[AttributeKey.stringKey("gen_ai.error.type")])
            assertEquals("invalid_request_error", span.attributes[AttributeKey.stringKey("error.type")])
            assertEquals(StatusCode.ERROR, span.status.statusCode)
        } finally {
            server.shutdown()
        }
    }
}
