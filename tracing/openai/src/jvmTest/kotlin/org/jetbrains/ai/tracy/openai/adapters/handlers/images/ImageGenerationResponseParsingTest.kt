/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.images

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageGenerationResponseParsingTest : BaseOpenTelemetryTracingTest() {

    private val testUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.openai.com",
        port = 443,
        pathSegments = listOf("v1", "images", "generations"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        },
    )

    private val noOpExtractor = object : MediaContentExtractor {
        override fun setUploadableContentAttributes(span: Span, field: String, content: MediaContent) = Unit
    }

    private fun makeResponse(body: kotlinx.serialization.json.JsonObject): TracyHttpResponse {
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(body)
            override val url: TracyHttpUrl = testUrl
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    @Test
    fun `handleImageGenerationResponseAttributes emits tracy response created not gen_ai response created`() {
        val responseBody = buildJsonObject {
            put("created", 1715000000)
            putJsonArray("data") {
                add(buildJsonObject { put("url", "https://example.com/image.png") })
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-image-created").startSpan()
        handleImageGenerationResponseAttributes(span, makeResponse(responseBody), noOpExtractor)
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-image-created" }

        assertEquals(
            "1715000000",
            spanData.attributes[AttributeKey.stringKey("tracy.response.created")],
            "Expected tracy.response.created to be set"
        )
        assertNull(
            spanData.attributes[AttributeKey.stringKey("gen_ai.response.created")],
            "gen_ai.response.created must not be set"
        )
    }

    @Test
    fun `handleImageGenerationResponseAttributes emits tracy response image url from first image`() {
        val imageUrl = "https://example.com/generated-image.png"
        val responseBody = buildJsonObject {
            put("created", 1715000000)
            putJsonArray("data") {
                add(buildJsonObject { put("url", imageUrl) })
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-image-url").startSpan()
        handleImageGenerationResponseAttributes(span, makeResponse(responseBody), noOpExtractor)
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-image-url" }

        assertEquals(
            imageUrl,
            spanData.attributes[AttributeKey.stringKey("tracy.response.image.url")],
            "Expected tracy.response.image.url to be set to the first image URL"
        )
    }

    @Test
    fun `handleImageGenerationResponseAttributes does not emit tracy response image url when no url present`() {
        val responseBody = buildJsonObject {
            put("created", 1715000000)
            putJsonArray("data") {
                add(buildJsonObject { put("b64_json", "base64encodeddata") })
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-image-no-url").startSpan()
        handleImageGenerationResponseAttributes(span, makeResponse(responseBody), noOpExtractor)
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-image-no-url" }

        assertNull(
            spanData.attributes[AttributeKey.stringKey("tracy.response.image.url")],
            "tracy.response.image.url should not be set when image data is base64 only"
        )
    }

    @Test
    fun `handleImageGenerationResponseAttributes only emits image url from first image in multi-image response`() {
        val firstUrl = "https://example.com/image-1.png"
        val secondUrl = "https://example.com/image-2.png"
        val responseBody = buildJsonObject {
            put("created", 1715000000)
            putJsonArray("data") {
                add(buildJsonObject { put("url", firstUrl) })
                add(buildJsonObject { put("url", secondUrl) })
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-multi-image-url").startSpan()
        handleImageGenerationResponseAttributes(span, makeResponse(responseBody), noOpExtractor)
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-multi-image-url" }

        assertEquals(
            firstUrl,
            spanData.attributes[AttributeKey.stringKey("tracy.response.image.url")],
            "tracy.response.image.url should be set to the first image URL only"
        )
    }
}
