/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.core.interceptors.OpenTelemetryOkHttpInterceptor
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
        url = "https://generativelanguage.googleapis.com/v1beta/cachedContents",
        parameters = emptyQueryParameters(),
    )

    private fun resourceUrl(id: String = "cachedContent123") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "cachedContents", id),
        url = "https://generativelanguage.googleapis.com/v1beta/cachedContents/$id",
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    /** Returns a [TracyQueryParameters] that resolves only the supplied key/value pairs. */
    private fun queryParameters(vararg entries: Pair<String, String>) = object : TracyQueryParameters {
        private val map = entries.toMap()
        override fun queryParameter(name: String): String? = map[name]
        override fun queryParameterValues(name: String): List<String?> = map[name]?.let { listOf(it) } ?: emptyList()
    }

    private fun resourceUrlWith(id: String = "cachedContent123", parameters: TracyQueryParameters) = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "cachedContents", id),
        url = "https://generativelanguage.googleapis.com/v1beta/cachedContents/$id",
        parameters = parameters,
    )

    // ─── Request / response factories ─────────────────────────────────────────

    private fun makeRequest(url: TracyHttpUrl, method: String, body: JsonObject? = null): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = if (body != null) TracyContentType.Application.Json else null
            override val body = if (body != null) TracyHttpRequestBody.Json(body) else TracyHttpRequestBody.Empty
            override val url = url
            override val method = method
        }

    private fun makeResponse(url: TracyHttpUrl, body: JsonObject): TracyHttpResponse =
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
        assertEquals("list", spanData.attributes[GEN_AI_OPERATION_NAME])
    }

    @Test
    fun `get request sets operation name to get`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "GET"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("get", spanData.attributes[GEN_AI_OPERATION_NAME])
    }

    @Test
    fun `post request sets operation name to create`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("create", spanData.attributes[GEN_AI_OPERATION_NAME])
    }

    @Test
    fun `patch request sets operation name to patch`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "PATCH", buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("patch", spanData.attributes[GEN_AI_OPERATION_NAME])
    }

    @Test
    fun `delete request sets operation name to delete`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "DELETE"))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("delete", spanData.attributes[GEN_AI_OPERATION_NAME])
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
        assertEquals("list", trace.attributes[GEN_AI_OPERATION_NAME])
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
        assertEquals("get", trace.attributes[GEN_AI_OPERATION_NAME])
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
        assertEquals("create", trace.attributes[GEN_AI_OPERATION_NAME])
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
        assertEquals("delete", trace.attributes[GEN_AI_OPERATION_NAME])
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

    // ─── CachedContent payload tracing tests ──────────────────────────────────

    @Test
    fun `create request traces model and displayName from CachedContent body`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val body = buildJsonObject {
            put("model", "models/gemini-2.5-flash")
            put("displayName", "my-cache")
        }
        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", body))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models/gemini-2.5-flash", spanData.attributes[GEN_AI_REQUEST_MODEL])
        assertEquals("my-cache", spanData.attributes[AttributeKey.stringKey("tracy.request.display_name")])
    }

    @Test
    fun `create request traces contents and tools and systemInstruction and toolConfig`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val sysInstr = buildJsonObject {
            putJsonArray("parts") { add(buildJsonObject { put("text", "You are helpful.") }) }
        }
        val toolConfig = buildJsonObject {
            putJsonObject("functionCallingConfig") { put("mode", "AUTO") }
        }
        val body = buildJsonObject {
            put("model", "models/gemini-2.5-flash")
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", "Background context") }) }
                })
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        add(buildJsonObject {
                            put("name", "get_weather")
                            put("description", "Get current weather")
                            putJsonObject("parameters") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("location") { put("type", "string") }
                                }
                            }
                        })
                    }
                })
            }
            put("systemInstruction", sysInstr)
            put("toolConfig", toolConfig)
        }
        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", body))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("user", spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.role")])
        assertEquals("Background context", spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")])
        assertEquals("get_weather", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.name")])
        assertEquals("Get current weather", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.description")])
        assertEquals("object", spanData.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.type")])
        assertEquals(sysInstr.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.system_instruction")])
        assertEquals(toolConfig.toString(), spanData.attributes[AttributeKey.stringKey("tracy.request.tool_config")])
    }

    @Test
    fun `create request traces ttl when present`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val body = buildJsonObject {
            put("model", "models/gemini-2.5-flash")
            put("ttl", "3600s")
        }
        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", body))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("3600s", spanData.attributes[AttributeKey.stringKey("tracy.request.ttl")])
    }

    @Test
    fun `create response traces name as gen_ai response id and cached_content name`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        // Need a POST that fires handleRequest first so the route is detected for the response too.
        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", buildJsonObject {}))

        val responseBody = buildJsonObject {
            put("name", "cachedContents/abc-xyz")
            put("model", "models/gemini-2.5-flash")
        }
        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(responseBody)
            override val url = listUrl()
            override val requestMethod = "POST"
            override fun isError() = false
        }
        handler.handleResponseAttributes(span, response)
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents/abc-xyz", spanData.attributes[GEN_AI_RESPONSE_ID])
        assertEquals("cachedContents/abc-xyz", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.name")])
    }

    @Test
    fun `create response traces model and createTime and expireTime and usageMetadata`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(listUrl(), "POST", buildJsonObject {}))

        val usage = buildJsonObject { put("totalTokenCount", 123) }
        val responseBody = buildJsonObject {
            put("name", "cachedContents/abc")
            put("model", "models/gemini-2.5-flash")
            put("createTime", "2024-10-02T15:01:23Z")
            put("expireTime", "2024-10-02T16:01:23Z")
            put("usageMetadata", usage)
        }
        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(responseBody)
            override val url = listUrl()
            override val requestMethod = "POST"
            override fun isError() = false
        }
        handler.handleResponseAttributes(span, response)
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models/gemini-2.5-flash", spanData.attributes[GEN_AI_RESPONSE_MODEL])
        assertEquals("2024-10-02T15:01:23Z", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.create_time")])
        assertEquals("2024-10-02T16:01:23Z", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.expire_time")])
        assertEquals(usage.toString(), spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.usage_metadata")])
    }

    @Test
    fun `get response traces full CachedContent response body`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "GET"))

        val responseBody = buildJsonObject {
            put("name", "cachedContents/abc")
            put("model", "models/gemini-2.5-flash")
            put("displayName", "doc-cache")
            put("createTime", "2024-10-02T15:01:23Z")
            put("updateTime", "2024-10-02T15:30:00Z")
            put("expireTime", "2024-10-02T16:01:23Z")
        }
        handler.handleResponseAttributes(span, makeResponse(resourceUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents/abc", spanData.attributes[GEN_AI_RESPONSE_ID])
        assertEquals("models/gemini-2.5-flash", spanData.attributes[GEN_AI_RESPONSE_MODEL])
        assertEquals("doc-cache", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.display_name")])
        assertEquals("2024-10-02T15:30:00Z", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.update_time")])
    }

    @Test
    fun `patch request traces updateMask query parameter`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val url = resourceUrlWith(parameters = queryParameters("updateMask" to "expireTime"))
        handler.handleRequestAttributes(span, makeRequest(url, "PATCH", buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("expireTime", spanData.attributes[AttributeKey.stringKey("tracy.request.update_mask")])
    }

    @Test
    fun `patch request traces partial CachedContent fields when only some are present`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val body = buildJsonObject {
            // PATCH typically only updates expiration; the helper must handle missing fields.
            put("expireTime", "2025-01-01T00:00:00Z")
        }
        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "PATCH", body))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("2025-01-01T00:00:00Z", spanData.attributes[AttributeKey.stringKey("tracy.request.expire_time")])
        // model/displayName/etc. weren't in the body, must be absent.
        assertNull(spanData.attributes[GEN_AI_REQUEST_MODEL])
        assertNull(spanData.attributes[AttributeKey.stringKey("tracy.request.display_name")])
    }

    @Test
    fun `patch response traces updated CachedContent`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "PATCH", buildJsonObject {}))

        val responseBody = buildJsonObject {
            put("name", "cachedContents/abc")
            put("model", "models/gemini-2.5-flash")
            put("expireTime", "2025-01-01T00:00:00Z")
            put("updateTime", "2024-12-31T23:00:00Z")
        }
        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(responseBody)
            override val url = resourceUrl()
            override val requestMethod = "PATCH"
            override fun isError() = false
        }
        handler.handleResponseAttributes(span, response)
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents/abc", spanData.attributes[GEN_AI_RESPONSE_ID])
        assertEquals("2025-01-01T00:00:00Z", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.expire_time")])
        assertEquals("2024-12-31T23:00:00Z", spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.update_time")])
    }

    @Test
    fun `list response traces per-item name and model and displayName`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        val responseBody = buildJsonObject {
            putJsonArray("cachedContents") {
                add(buildJsonObject {
                    put("name", "cachedContents/first")
                    put("model", "models/gemini-2.5-flash")
                    put("displayName", "first-cache")
                })
                add(buildJsonObject {
                    put("name", "cachedContents/second")
                    put("model", "models/gemini-2.5-pro")
                    // no displayName on this one
                })
            }
        }
        handler.handleResponseAttributes(span, makeResponse(listUrl(), responseBody))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("cachedContents/first", spanData.attributes[AttributeKey.stringKey("tracy.response.list.0.name")])
        assertEquals("models/gemini-2.5-flash", spanData.attributes[AttributeKey.stringKey("tracy.response.list.0.model")])
        assertEquals("first-cache", spanData.attributes[AttributeKey.stringKey("tracy.response.list.0.display_name")])

        assertEquals("cachedContents/second", spanData.attributes[AttributeKey.stringKey("tracy.response.list.1.name")])
        assertEquals("models/gemini-2.5-pro", spanData.attributes[AttributeKey.stringKey("tracy.response.list.1.model")])
        assertNull(spanData.attributes[AttributeKey.stringKey("tracy.response.list.1.display_name")])

        // count + has_more are still set by the LIST handler itself.
        assertEquals(2L, spanData.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
        assertEquals("false", spanData.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
    }

    @Test
    fun `delete request and response do not trace CachedContent`() {
        val handler = GeminiCachedContentsHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(resourceUrl(), "DELETE"))
        // DELETE response body is `{}` per the API spec.
        val response = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(buildJsonObject {})
            override val url = resourceUrl()
            override val requestMethod = "DELETE"
            override fun isError() = false
        }
        handler.handleResponseAttributes(span, response)
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("delete", spanData.attributes[GEN_AI_OPERATION_NAME])
        // No CachedContent attributes are emitted for delete.
        assertNull(spanData.attributes[AttributeKey.stringKey("tracy.response.cached_content.name")])
        assertNull(spanData.attributes[GEN_AI_RESPONSE_ID])
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
        assertEquals("generateContent", trace.attributes[GEN_AI_OPERATION_NAME])
        assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }
}
