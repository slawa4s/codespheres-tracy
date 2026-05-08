/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonPrimitive
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
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiEmbedHandler] and the [GeminiLLMTracingAdapter] routing logic.
 *
 * These tests use in-process span capture and do **not** require a live API key or
 * LLM provider. Both the native Gemini embedContent layout and the Vertex AI predict
 * layout (used by LiteLLM proxies) are covered.
 */
class GeminiEmbedHandlerTest : BaseAITracingTest() {

    // ─── URL helpers ──────────────────────────────────────────────────────────

    private fun embedContentUrl(model: String = "gemini-embedding-001") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "models", "$model:embedContent"),
        parameters = emptyQueryParameters(),
    )

    private fun predictEmbedUrl(model: String = "gemini-embedding-001") = TracyHttpUrlImpl(
        scheme = "https",
        host = "us-central1-aiplatform.googleapis.com",
        port = 443,
        pathSegments = listOf("v1", "projects", "my-project", "locations", "us-central1",
            "publishers", "google", "models", "$model:predict"),
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    // ─── Request / response factories ─────────────────────────────────────────

    private fun makeRequest(url: TracyHttpUrl, body: kotlinx.serialization.json.JsonObject): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Json(body)
            override val url = url
            override val method = "POST"
        }

    private fun makeResponse(url: TracyHttpUrl, body: kotlinx.serialization.json.JsonObject): TracyHttpResponse =
        object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(body)
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = false
        }

    /** Builds a JSON array of [size] dummy float primitives to simulate an embedding vector. */
    private fun buildEmbeddingValues(size: Int) = buildJsonArray {
        repeat(size) { add(JsonPrimitive(0.1)) }
    }

    // ─── GeminiEmbedHandler request tests ─────────────────────────────────────

    @Test
    fun `native embedContent request sets gemini api type`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(embedContentUrl(), buildJsonObject {
            put("taskType", "RETRIEVAL_DOCUMENT")
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `native embedContent request extracts taskType`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(embedContentUrl(), buildJsonObject {
            put("taskType", "RETRIEVAL_DOCUMENT")
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("RETRIEVAL_DOCUMENT", spanData.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
    }

    @Test
    fun `native embedContent request extracts outputDimensionality`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(embedContentUrl(), buildJsonObject {
            put("outputDimensionality", 256)
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(256L, spanData.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }

    @Test
    fun `vertex ai predict request extracts task_type from instances`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject {
                    put("task_type", "SEMANTIC_SIMILARITY")
                    put("content", "hello world")
                })
            }
            putJsonObject("parameters") {
                put("outputDimensionality", 512)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("SEMANTIC_SIMILARITY", spanData.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
    }

    @Test
    fun `vertex ai predict request extracts outputDimensionality from parameters`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("content", "hello world") })
            }
            putJsonObject("parameters") {
                put("outputDimensionality", 768)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(768L, spanData.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }

    @Test
    fun `missing optional fields do not produce task_type or output_dimensionality attributes`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(embedContentUrl(), buildJsonObject {
            put("content", buildJsonObject { })
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
        assertNull(spanData.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
    }

    // ─── GeminiEmbedHandler response tests ────────────────────────────────────

    @Test
    fun `native embedContent response sets gen_ai output type to embedding`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(embedContentUrl(), buildJsonObject {
            putJsonObject("embedding") {
                put("values", buildEmbeddingValues(768))
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("embedding", spanData.attributes[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `native embedContent response extracts embedding dimension`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(embedContentUrl(), buildJsonObject {
            putJsonObject("embedding") {
                put("values", buildEmbeddingValues(768))
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(768L, spanData.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
    }

    @Test
    fun `vertex ai predict response sets gen_ai output type to embedding`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(predictEmbedUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    putJsonObject("embeddings") {
                        put("values", buildEmbeddingValues(256))
                        put("statistics", buildJsonObject { put("truncated", false) })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("embedding", spanData.attributes[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `vertex ai predict response extracts embedding dimension`() {
        val handler = GeminiEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(predictEmbedUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    putJsonObject("embeddings") {
                        put("values", buildEmbeddingValues(256))
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(256L, spanData.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
    }

    // ─── GeminiLLMTracingAdapter routing + operation name tests (via OkHttp) ──

    /**
     * Makes a direct OkHttp POST to a [MockWebServer] using an [OkHttpClient] instrumented
     * with [GeminiLLMTracingAdapter], then returns after the spans are recorded.
     *
     * The [path] must end with the model:operation segment so the adapter can parse it.
     */
    private fun withAdapterRequest(
        path: String,
        requestBody: String,
        responseBody: String = "{}",
        block: () -> Unit = {}
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

            val request = Request.Builder()
                .url(server.url(path))
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { }
            } catch (_: Exception) {
                // Ignore SDK-level validation errors; spans are already recorded.
            }

            block()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `adapter sets operation name to embedContent for native embedContent URL`() {
        withAdapterRequest(
            path = "/v1beta/models/gemini-embedding-001:embedContent",
            requestBody = buildJsonObject { put("taskType", "RETRIEVAL_DOCUMENT") }.toString(),
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("embedContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `adapter overrides operation name to embedContent for predict URL with embedding model`() {
        withAdapterRequest(
            path = "/v1/projects/proj/locations/us-central1/publishers/google/models/gemini-embedding-001:predict",
            requestBody = buildJsonObject {
                putJsonArray("instances") {
                    add(buildJsonObject {
                        put("task_type", "RETRIEVAL_DOCUMENT")
                        put("content", "some text")
                    })
                }
            }.toString(),
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        // Must be "embedContent", NOT "predict"
        assertEquals("embedContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `adapter sets embedding response attributes for embedContent URL`() {
        val responseBody = buildJsonObject {
            putJsonObject("embedding") {
                put("values", buildEmbeddingValues(512))
            }
        }.toString()

        withAdapterRequest(
            path = "/v1beta/models/gemini-embedding-001:embedContent",
            requestBody = "{}",
            responseBody = responseBody,
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        assertEquals("embedding", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        assertEquals(512L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
    }

    @Test
    fun `adapter does NOT route generateContent to embed handler`() {
        withAdapterRequest(
            path = "/v1beta/models/gemini-2.5-flash:generateContent",
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
        // generateContent URL must produce "generateContent" operation, not "embedContent"
        assertEquals("generateContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        // GeminiContentGenHandler always sets gemini.api.type = "models"
        assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `adapter does NOT route imagen predict to embed handler`() {
        withAdapterRequest(
            path = "/v1/models/imagen-4.0-generate-001:predict",
            requestBody = buildJsonObject {
                putJsonArray("instances") {
                    add(buildJsonObject { put("prompt", "a cat") })
                }
            }.toString(),
        )

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()
        // imagen:predict must stay as "predict"
        assertEquals("predict", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        assertNull(trace.attributes[AttributeKey.stringKey("gemini.api.type")])
    }
}
