/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.gemini.adapters.handlers.cachedcontents.GeminiCachedContentsHandler
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiCachedContentsHandler] and the [GeminiLLMTracingAdapter] routing logic.
 *
 * These tests use in-process span capture and do **not** require a live API key or
 * LLM provider. All five CRUD operations on the cachedContents resource are covered.
 */
class GeminiCachedContentsHandlerTest : BaseAITracingTest() {

    // ─── URL helpers ──────────────────────────────────────────────────────────

    private fun listUrl() = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "cachedContents"),
        parameters = emptyQueryParameters(),
    )

    private fun resourceUrl(id: String = "cachedContent123") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "cachedContents", id),
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    // ─── Request / response factories ─────────────────────────────────────────

    private fun makeRequest(url: TracyHttpUrl, method: String, body: kotlinx.serialization.json.JsonObject? = null): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = if (body != null) TracyContentType.Application.Json else null
            override val body = if (body != null) TracyHttpRequestBody.Json(body) else TracyHttpRequestBody.Empty
            override val url = url
            override val method = method
        }

    private fun makeResponse(url: TracyHttpUrl, body: kotlinx.serialization.json.JsonObject): TracyHttpResponse =
        object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(body)
            override val url = url
            override val requestMethod = "GET"
            override fun isError() = false
        }

    // ─── GeminiCachedContentsHandler request tests ────────────────────────────

    @Test
    fun `list request sets gemini api type to cachedContents`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(listUrl(), "GET"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `list request sets operation name to list`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(listUrl(), "GET"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("list", spanData.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `get request sets operation name to get`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "GET"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("get", spanData.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `post request sets operation name to create`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("create", spanData.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `patch request sets operation name to update`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "PATCH", buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("update", spanData.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `delete request sets operation name to delete`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "DELETE"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("delete", spanData.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    // ─── GeminiCachedContentsHandler response tests ───────────────────────────

    @Test
    fun `list response with items sets list count`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {
                add(buildJsonObject { put("name", "cachedContents/abc1") })
                add(buildJsonObject { put("name", "cachedContents/abc2") })
                add(buildJsonObject { put("name", "cachedContents/abc3") })
            }
        }

        handler.handleResponseAttributes(span, makeResponse(listUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(3L, spanData.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
    }

    @Test
    fun `list response without nextPageToken sets has_more to false`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {
                add(buildJsonObject { put("name", "cachedContents/abc1") })
            }
        }

        handler.handleResponseAttributes(span, makeResponse(listUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("false", spanData.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
    }

    @Test
    fun `list response with nextPageToken sets has_more to true`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {
                add(buildJsonObject { put("name", "cachedContents/abc1") })
            }
            put("nextPageToken", "pageToken123")
        }

        handler.handleResponseAttributes(span, makeResponse(listUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("true", spanData.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
    }

    @Test
    fun `list response with empty cachedContents array sets count to zero`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {}
        }

        handler.handleResponseAttributes(span, makeResponse(listUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(0L, spanData.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
    }

    @Test
    fun `non-list response without cachedContents array sets no list attributes`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            put("name", "cachedContents/abc1")
            put("model", "models/gemini-1.5-pro")
        }

        handler.handleResponseAttributes(span, makeResponse(resourceUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
    }

    // ─── GeminiLLMTracingAdapter routing tests (via OkHttp) ───────────────────

    /**
     * Issues an HTTP request to a [MockWebServer] using an [OkHttpClient] instrumented
     * with [GeminiLLMTracingAdapter] and returns after the spans are recorded.
     */
    private fun withAdapterRequest(
        path: String,
        method: String = "GET",
        requestBody: String? = null,
        responseBody: String = "{}",
    ) {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(responseBody)
            )

            val client = OkHttpClient.Builder()
                .addInterceptor(OpenTelemetryOkHttpInterceptor(adapter = GeminiLLMTracingAdapter()))
                .build()

            val okBody = requestBody?.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(server.url(path))
                .method(method, okBody)
                .build()

            try {
                client.newCall(request).execute().use { }
            } catch (_: Exception) {
                // Ignore SDK-level validation errors; spans are already recorded.
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `adapter routes list cachedContents URL to cached contents handler`() {
        withAdapterRequest(
            path = "/v1beta/cachedContents",
            method = "GET",
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        assertEquals("list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        // Model must NOT be set to "cachedContents" (a garbage value from URL parsing)
        assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `adapter routes get cachedContent URL to cached contents handler`() {
        withAdapterRequest(
            path = "/v1beta/cachedContents/caches%2Fabc123",
            method = "GET",
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        assertEquals("get", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `adapter routes post cachedContents URL to cached contents handler`() {
        withAdapterRequest(
            path = "/v1beta/cachedContents",
            method = "POST",
            requestBody = buildJsonObject {
                put("model", "models/gemini-1.5-pro")
            }.toString(),
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        assertEquals("create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `adapter routes delete cachedContent URL to cached contents handler`() {
        withAdapterRequest(
            path = "/v1beta/cachedContents/caches%2Fabc123",
            method = "DELETE",
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        assertEquals("delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `adapter sets list response attributes for cachedContents list URL`() {
        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {
                add(buildJsonObject { put("name", "cachedContents/a") })
                add(buildJsonObject { put("name", "cachedContents/b") })
            }
            put("nextPageToken", "token123")
        }.toString()

        withAdapterRequest(
            path = "/v1beta/cachedContents",
            method = "GET",
            responseBody = responseBody,
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
        assertEquals("true", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
    }

    @Test
    fun `adapter does NOT route generateContent to cached contents handler`() {
        withAdapterRequest(
            path = "/v1beta/models/gemini-2.5-flash:generateContent",
            method = "POST",
            requestBody = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", "hello") })
                        }
                    })
                }
            }.toString(),
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        // generateContent URL must NOT be routed to cached contents handler
        assertEquals("generateContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }
}
