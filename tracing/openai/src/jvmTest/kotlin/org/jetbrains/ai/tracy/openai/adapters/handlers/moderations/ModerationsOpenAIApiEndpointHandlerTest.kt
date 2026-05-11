/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ModerationsOpenAIApiEndpointHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("openai")
class ModerationsOpenAIApiEndpointHandlerTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracer: Tracer

    @BeforeEach
    fun setup() {
        spanExporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        tracer = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build()
            .getTracer("moderations-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun url(): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.openai.com",
        port = 443,
        pathSegments = listOf("v1", "moderations"),
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String>()
        }
    )

    private fun jsonRequest(jsonBody: String): TracyHttpRequest {
        val elem = Json.parseToJsonElement(jsonBody)
        val body = TracyHttpRequestBody.Json(elem)
        return body.asRequestView(TracyContentType.Application.Json, url(), "POST")
    }

    private fun jsonResponse(jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@ModerationsOpenAIApiEndpointHandlerTest.url()
            override val requestMethod = "POST"
            override fun isError() = false
        }
    }

    private fun captureRequest(jsonBody: String): Attributes {
        val handler = ModerationsOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, jsonRequest(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(jsonBody: String): Attributes {
        val handler = ModerationsOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── Request attribute tests ───────────────────────────────────────────────

    @Test
    fun `always sets operation name and api type`() {
        val attrs = captureRequest("""{"input":"some text"}""")
        assertEquals("moderations", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("moderations", attrs[AttributeKey.stringKey("openai.api.type")])
    }

    @Test
    fun `string input sets input type to string`() {
        val attrs = captureRequest("""{"input":"This is harmful content"}""")
        assertEquals("string", attrs[AttributeKey.stringKey("tracy.request.input.type")])
    }

    @Test
    fun `array of strings sets input type to string`() {
        val attrs = captureRequest("""{"input":["text one","text two"]}""")
        assertEquals("string", attrs[AttributeKey.stringKey("tracy.request.input.type")])
    }

    @Test
    fun `array with objects sets input type to multimodal`() {
        val attrs = captureRequest(
            """{"input":[{"type":"image_url","image_url":{"url":"https://example.com/img.png"}}]}"""
        )
        assertEquals("multimodal", attrs[AttributeKey.stringKey("tracy.request.input.type")])
    }

    @Test
    fun `mixed array with text and image object sets input type to multimodal`() {
        val attrs = captureRequest(
            """{"input":["some text",{"type":"image_url","image_url":{"url":"https://example.com/img.png"}}]}"""
        )
        assertEquals("multimodal", attrs[AttributeKey.stringKey("tracy.request.input.type")])
    }

    // ── Response attribute tests ──────────────────────────────────────────────

    private val basicResponse = """
        {
          "id": "modr-test123",
          "model": "omni-moderation-latest",
          "results": [
            {
              "flagged": true,
              "categories": {"violence": true, "sexual": false},
              "category_scores": {"violence": 0.95, "sexual": 0.01}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `results count is set from results array size`() {
        val attrs = captureResponse(basicResponse)
        assertEquals(1, attrs[AttributeKey.longKey("tracy.response.results.count")]?.toInt())
    }

    @Test
    fun `flagged is set from first result`() {
        val attrs = captureResponse(basicResponse)
        assertEquals(true, attrs[AttributeKey.booleanKey("tracy.response.results.flagged")])
    }

    @Test
    fun `categories is set as JSON string`() {
        val attrs = captureResponse(basicResponse)
        val categories = attrs[AttributeKey.stringKey("tracy.response.results.categories")]
        assertTrue(categories != null && categories.contains("violence"))
    }

    @Test
    fun `category_scores is set as JSON string`() {
        val attrs = captureResponse(basicResponse)
        val scores = attrs[AttributeKey.stringKey("tracy.response.results.category_scores")]
        assertTrue(scores != null && scores.contains("violence"))
    }

    @Test
    fun `category_applied_input_types is set when present`() {
        val responseWithAppliedTypes = """
            {
              "id": "modr-test456",
              "model": "omni-moderation-latest",
              "results": [
                {
                  "flagged": false,
                  "categories": {"violence": false},
                  "category_scores": {"violence": 0.001},
                  "category_applied_input_types": {"violence": ["text","image"]}
                }
              ]
            }
        """.trimIndent()
        val attrs = captureResponse(responseWithAppliedTypes)
        val applied = attrs[AttributeKey.stringKey("tracy.response.results.category_applied_input_types")]
        assertTrue(applied != null && applied.contains("violence"))
    }

    @Test
    fun `category_applied_input_types is absent when not in response`() {
        val attrs = captureResponse(basicResponse)
        assertNull(attrs[AttributeKey.stringKey("tracy.response.results.category_applied_input_types")])
    }

    @Test
    fun `multiple results only reads first result flagged status`() {
        val multiResponse = """
            {
              "id": "modr-multi",
              "model": "omni-moderation-latest",
              "results": [
                {
                  "flagged": false,
                  "categories": {"violence": false},
                  "category_scores": {"violence": 0.001}
                },
                {
                  "flagged": true,
                  "categories": {"violence": true},
                  "category_scores": {"violence": 0.99}
                }
              ]
            }
        """.trimIndent()
        val attrs = captureResponse(multiResponse)
        assertEquals(2, attrs[AttributeKey.longKey("tracy.response.results.count")]?.toInt())
        // only first result
        assertEquals(false, attrs[AttributeKey.booleanKey("tracy.response.results.flagged")])
    }

    @Test
    fun `empty results array sets count to zero and no other result attributes`() {
        val emptyResponse = """{"id":"modr-empty","model":"text-moderation-latest","results":[]}"""
        val attrs = captureResponse(emptyResponse)
        assertEquals(0, attrs[AttributeKey.longKey("tracy.response.results.count")]?.toInt())
        assertNull(attrs[AttributeKey.booleanKey("tracy.response.results.flagged")])
    }

    @Test
    fun `handleStreaming is a no-op`() {
        val handler = ModerationsOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleStreaming(span, "data: {}")
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
