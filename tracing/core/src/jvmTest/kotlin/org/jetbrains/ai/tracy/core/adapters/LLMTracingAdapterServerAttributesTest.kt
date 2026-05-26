/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core.adapters

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.ai.tracy.core.http.parsers.SseEvent
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LLMTracingAdapterServerAttributesTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var sdk: OpenTelemetrySdk

    private val adapter = object : LLMTracingAdapter("test-system") {
        override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) = Unit
        override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) = Unit
        override fun getSpanName(): String = "test-span"
        override fun registerResponseStreamEvent(
            span: Span,
            url: TracyHttpUrl,
            event: SseEvent,
            index: Long,
        ): Result<Unit> = Result.success(Unit)
    }

    @BeforeEach
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "test")))
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()
    }

    private fun makeRequest(host: String, port: Int): TracyHttpRequest {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = host,
            port = port,
            pathSegments = listOf("v1", "chat", "completions"),
            url = "https://$host:$port/v1/chat/completions",
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String): String? = null
                override fun queryParameterValues(name: String): List<String?> = emptyList()
            },
        )
        return TracyHttpRequestBody.Json(buildJsonObject {}).asRequestView(
            contentType = null,
            url = url,
            method = "POST"
        )
    }

    private fun recordSpan(host: String, port: Int): SpanData {
        val tracer = sdk.getTracer("test")
        val span = tracer.spanBuilder("test-span").startSpan()
        adapter.registerRequest(span, makeRequest(host, port))
        span.end()
        return spanExporter.finishedSpanItems.last()
    }

    @Test
    fun `server address attribute is set from request host`() {
        val span = recordSpan("api.openai.com", 443)
        assertEquals("api.openai.com", span.attributes[AttributeKey.stringKey("server.address")])
    }

    @Test
    fun `server port attribute is set from request port`() {
        val span = recordSpan("api.openai.com", 443)
        assertEquals(443L, span.attributes[AttributeKey.longKey("server.port")])
    }

    @Test
    fun `server address and port reflect arbitrary host and port values`() {
        val host = "127.0.0.1"
        val port = 54321
        val span = recordSpan(host, port)
        assertEquals(host, span.attributes[AttributeKey.stringKey("server.address")])
        assertEquals(port.toLong(), span.attributes[AttributeKey.longKey("server.port")])
    }
}
