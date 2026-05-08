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
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [GeminiImagenHandler].
 *
 * These tests use in-process span capture and do **not** require a live API key or LLM provider.
 */
class GeminiImagenHandlerTest : BaseAITracingTest() {

    private val handler = GeminiImagenHandler(MediaContentExtractorImpl())

    // ─── URL helpers ──────────────────────────────────────────────────────────

    private fun imagenUrl(model: String = "imagen-4.0-generate-001") = TracyHttpUrlImpl(
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

    // ─── Request attribute tests ───────────────────────────────────────────────

    @Test
    fun `request sets gemini api type to models`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(imagenUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("prompt", "a cat") })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `request extracts sampleCount as number_of_images`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(imagenUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("prompt", "a cat") })
            }
            putJsonObject("parameters") {
                put("sampleCount", 4)
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(4L, spanData.attributes[AttributeKey.longKey("gen_ai.request.image.number_of_images")])
    }

    @Test
    fun `request without sampleCount does not set number_of_images`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(imagenUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("prompt", "a cat") })
            }
            putJsonObject("parameters") {
                put("aspectRatio", "1:1")
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.longKey("gen_ai.request.image.number_of_images")])
    }

    @Test
    fun `request without parameters does not set number_of_images`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleRequestAttributes(span, makeRequest(imagenUrl(), buildJsonObject {
            putJsonArray("instances") {
                add(buildJsonObject { put("prompt", "a cat") })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertNull(spanData.attributes[AttributeKey.longKey("gen_ai.request.image.number_of_images")])
    }

    // ─── Response attribute tests ──────────────────────────────────────────────

    @Test
    fun `response sets gen_ai output type to image`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(imagenUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    put("mimeType", "image/png")
                    put("bytesBase64Encoded", "abc123")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("image", spanData.attributes[AttributeKey.stringKey("gen_ai.output.type")])
    }

    @Test
    fun `response sets image count to number of predictions`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(imagenUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    put("mimeType", "image/png")
                    put("bytesBase64Encoded", "abc1")
                })
                add(buildJsonObject {
                    put("mimeType", "image/png")
                    put("bytesBase64Encoded", "abc2")
                })
                add(buildJsonObject {
                    put("mimeType", "image/png")
                    put("bytesBase64Encoded", "abc3")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(3L, spanData.attributes[AttributeKey.longKey("gen_ai.response.image.count")])
    }

    @Test
    fun `response with empty predictions sets image count to zero`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(imagenUrl(), buildJsonObject {
            putJsonArray("predictions") {}
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(0L, spanData.attributes[AttributeKey.longKey("gen_ai.response.image.count")])
    }

    @Test
    fun `response with single prediction sets image count to one`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler.handleResponseAttributes(span, makeResponse(imagenUrl(), buildJsonObject {
            putJsonArray("predictions") {
                add(buildJsonObject {
                    put("mimeType", "image/jpeg")
                    put("bytesBase64Encoded", "someBase64Data")
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals(1L, spanData.attributes[AttributeKey.longKey("gen_ai.response.image.count")])
        assertEquals("image", spanData.attributes[AttributeKey.stringKey("gen_ai.output.type")])
    }
}
