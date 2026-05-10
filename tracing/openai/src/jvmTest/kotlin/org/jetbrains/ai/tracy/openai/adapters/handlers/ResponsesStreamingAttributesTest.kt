/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for streaming attribute extraction in [ResponsesOpenAIApiEndpointHandler].
 *
 * Exercises [ResponsesOpenAIApiEndpointHandler.handleRequestAttributes] and
 * [ResponsesOpenAIApiEndpointHandler.handleStreaming] in isolation using in-memory spans.
 * No network calls and no real API keys are required.
 */
@Tag("openai")
class ResponsesStreamingAttributesTest {

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
            .getTracer("responses-streaming-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    @Test
    fun streamingResponseCompletedEventPopulatesAllAttributes() {
        val handler = ResponsesOpenAIApiEndpointHandler(MediaContentExtractorImpl())
        val span = tracer.spanBuilder("test").startSpan()

        // Request path sets openai.api.type and gen_ai.operation.name
        val requestBody = TracyHttpRequestBody.Json(
            Json.parseToJsonElement("""{"model":"gpt-4o-mini","input":"Hello","stream":true}""")
        )
        val request = requestBody.asRequestView(
            contentType = TracyContentType.Application.Json,
            url = TracyHttpUrlImpl(
                scheme = "https",
                host = "api.openai.com",
                port = 443,
                pathSegments = listOf("v1", "responses"),
                parameters = object : TracyQueryParameters {
                    override fun queryParameter(name: String) = null
                    override fun queryParameterValues(name: String) = emptyList<String?>()
                }
            ),
            method = "POST",
        )
        handler.handleRequestAttributes(span, request)

        // Streaming path: response.completed event carries all response-level attributes
        val sseEvents = buildString {
            appendLine("""data: {"type":"response.output_text.done","text":"Hello world"}""")
            appendLine()
            appendLine(
                """data: {"type":"response.completed","response":{"id":"resp_abc123","object":"response","status":"completed","model":"gpt-4o-mini","created_at":1234567890,"completed_at":1234567899,"usage":{"input_tokens":10,"output_tokens":5}}}"""
            )
            appendLine()
            appendLine("data: [DONE]")
        }
        handler.handleStreaming(span, sseEvents)

        span.end()

        val attrs = spanExporter.finishedSpanItems.last().attributes

        assertEquals("generate_content", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("responses", attrs[AttributeKey.stringKey("openai.api.type")])
        assertEquals("resp_abc123", attrs[AttributeKey.stringKey("gen_ai.response.id")])
        assertEquals("gpt-4o-mini", attrs[AttributeKey.stringKey("gen_ai.response.model")])
        assertEquals("response", attrs[AttributeKey.stringKey("tracy.response.object")])
        assertEquals("completed", attrs[AttributeKey.stringKey("tracy.response.status")])
        assertEquals(1234567890L, attrs[AttributeKey.longKey("tracy.response.created_at")])
        assertEquals(1234567899L, attrs[AttributeKey.longKey("tracy.response.completed_at")])
        assertNotNull(attrs[AttributeKey.longKey("gen_ai.usage.input_tokens")], "gen_ai.usage.input_tokens")
        assertNotNull(attrs[AttributeKey.longKey("gen_ai.usage.output_tokens")], "gen_ai.usage.output_tokens")
    }
}
