/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val TEST_SYSTEM = "test-provider"

private val testAdapter = object : LLMTracingAdapter(TEST_SYSTEM) {
    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {}
    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {}
    override fun getSpanName(request: TracyHttpRequest) = "test-span"
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {}
}

class LLMTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    @Test
    fun `registerRequest emits server address and port`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"object":"ok"}""")
        )

        try {
            val client = instrument(OkHttpClient(), testAdapter)
            val body = """{"model":"test"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .post(body)
                .build()

            client.newCall(request).execute().close()

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            val span = spans.first()

            val serverAddress = span.attributes[AttributeKey.stringKey("server.address")]
            assertNotNull(serverAddress, "server.address must be set")
            assertEquals(server.hostName, serverAddress)

            val serverPort = span.attributes[AttributeKey.longKey("server.port")]
            assertNotNull(serverPort, "server.port must be set")
            assertEquals(server.port.toLong(), serverPort)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `registerRequest emits gen_ai_provider_name`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"object":"ok"}""")
        )

        try {
            val client = instrument(OkHttpClient(), testAdapter)
            val body = """{"model":"test"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .post(body)
                .build()

            client.newCall(request).execute().close()

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            val span = spans.first()

            assertEquals(TEST_SYSTEM, span.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `registerResponse emits http_response_status_code`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"object":"ok"}""")
        )

        try {
            val client = instrument(OkHttpClient(), testAdapter)
            val body = """{"model":"test"}""".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url("/v1/test"))
                .post(body)
                .build()

            client.newCall(request).execute().close()

            val spans = analyzeSpans()
            assertEquals(1, spans.size)
            val span = spans.first()

            val statusCode = span.attributes[AttributeKey.longKey("http.response.status_code")]
            assertNotNull(statusCode, "http.response.status_code must be set")
            assertEquals(200L, statusCode)
        } finally {
            server.shutdown()
        }
    }
}
