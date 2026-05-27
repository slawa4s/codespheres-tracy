/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiVertexEmbedHandler].
 *
 * Covers the Vertex AI predict layout for embedding models — `instances[]` for inputs and
 * `predictions[].embeddings` for outputs. Routing (`:predict` URL → this handler) is covered
 * by the adapter tests in [GeminiEmbedHandlerTest].
 */
class GeminiVertexEmbedHandlerTest : BaseAITracingTest() {

    // ─── URL helpers ──────────────────────────────────────────────────────────

    private fun predictEmbedUrl(model: String = "gemini-embedding-001") = TracyHttpUrlImpl(
        scheme = "https",
        host = "us-central1-aiplatform.googleapis.com",
        port = 443,
        pathSegments = listOf(
            "v1", "projects", "my-project", "locations", "us-central1",
            "publishers", "google", "models", "$model:predict",
        ),
        url = "https://us-central1-aiplatform.googleapis.com/v1/projects/my-project/locations/us-central1/publishers/google/models/$model:predict",
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    // ─── Request / response factories ─────────────────────────────────────────

    private fun makeRequest(url: TracyHttpUrl, body: JsonObject): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Json(body)
            override val url = url
            override val method = "POST"
        }

    private fun makeResponse(url: TracyHttpUrl, body: JsonObject): TracyHttpResponse =
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

    // ─── Request tests ────────────────────────────────────────────────────────

    @Test
    fun `vertex predict request sets gemini api type to models`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("content", "hello") })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `vertex predict request traces task_type from instances first element`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject {
                    put("task_type", "SEMANTIC_SIMILARITY")
                    put("content", "hello world")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("SEMANTIC_SIMILARITY", spanData.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
    }

    @Test
    fun `vertex predict request traces content from instances first element`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject {
                    put("content", "I would like embeddings for this text!")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(
            "I would like embeddings for this text!",
            spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")]
        )
    }

    @Test
    fun `vertex predict request traces title from instances first element`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject {
                    put("title", "document title")
                    put("content", "body")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("document title", spanData.attributes[AttributeKey.stringKey("gen_ai.request.title")])
    }

    @Test
    fun `vertex predict request traces parameters autoTruncate`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("content", "x") })
            }
            putJsonObject("parameters") {
                put("autoTruncate", true)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(true, spanData.attributes[AttributeKey.booleanKey("gen_ai.request.auto_truncate")])
    }

    @Test
    fun `vertex predict request traces parameters outputDimensionality`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("content", "x") })
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
    fun `vertex predict request without instances does not crash`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(predictEmbedUrl(), buildJsonObject {
            putJsonObject("parameters") {
                put("outputDimensionality", 512)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(512L, spanData.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")])
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
        assertNull(spanData.attributes[AttributeKey.stringKey("gen_ai.request.title")])
    }

    // ─── Response tests ───────────────────────────────────────────────────────

    @Test
    fun `vertex predict response sets gen_ai output type to embedding`() {
        val handler = GeminiVertexEmbedHandler()
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
        assertEquals("embedding", spanData.attributes[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `vertex predict response extracts embedding values and dimension`() {
        val handler = GeminiVertexEmbedHandler()
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
        // The values themselves are also stored (serialized JSON, possibly redacted under policy).
        val values = spanData.attributes[AttributeKey.stringKey("gen_ai.response.embedding.values")]
        assertEquals(true, values != null && values.isNotEmpty())
    }

    @Test
    fun `vertex predict response extracts statistics truncated and token_count`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(predictEmbedUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    putJsonObject("embeddings") {
                        put("values", buildEmbeddingValues(256))
                        putJsonObject("statistics") {
                            put("truncated", false)
                            put("token_count", 4)
                        }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(false, spanData.attributes[AttributeKey.booleanKey("gen_ai.response.embedding.statistics.truncated")])
        assertEquals(4L, spanData.attributes[AttributeKey.longKey("gen_ai.response.embedding.statistics.token_count")])
    }

    @Test
    fun `vertex predict response token_count populates gen_ai usage input_tokens`() {
        val handler = GeminiVertexEmbedHandler()
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(predictEmbedUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    putJsonObject("embeddings") {
                        put("values", buildEmbeddingValues(256))
                        putJsonObject("statistics") {
                            put("token_count", 11)
                        }
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        // Cross-format alignment with native `usageMetadata.promptTokenCount`.
        assertEquals(11L, spanData.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
    }
}
