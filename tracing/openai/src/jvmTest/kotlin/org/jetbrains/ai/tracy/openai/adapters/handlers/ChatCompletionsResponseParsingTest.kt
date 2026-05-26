/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertTrue

class ChatCompletionsResponseParsingTest : BaseOpenTelemetryTracingTest() {

    private val testUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.openai.com",
        port = 443,
        pathSegments = listOf("v1", "chat", "completions"),
        url = "https://api.openai.com/v1/chat/completions",
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        },
    )

    private val noOpExtractor = object : MediaContentExtractor {
        override fun setUploadableContentAttributes(span: Span, field: String, content: MediaContent) = Unit
    }

    private val handler = ChatCompletionsOpenAIApiEndpointHandler(noOpExtractor)

    private fun makeResponse(body: JsonObject): TracyHttpResponse {
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
    fun `handleResponseAttributes aggregates finish reasons into GEN_AI_RESPONSE_FINISH_REASONS`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    put("finish_reason", "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", "Hello!")
                    }
                })
                add(buildJsonObject {
                    put("index", 1)
                    put("finish_reason", "length")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", "Hi!")
                    }
                })
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-finish-reasons").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-finish-reasons" }

        val finishReasons = spanData.attributes[GEN_AI_RESPONSE_FINISH_REASONS]
        assertTrue(finishReasons != null && finishReasons.isNotEmpty(), "Expected GEN_AI_RESPONSE_FINISH_REASONS to be set")
        assertTrue(finishReasons.contains("stop"), "Expected 'stop' in finish reasons")
        assertTrue(finishReasons.contains("length"), "Expected 'length' in finish reasons")
    }

    @Test
    fun `handleResponseAttributes does not set GEN_AI_RESPONSE_FINISH_REASONS when choices are empty`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-no-finish-reasons").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-no-finish-reasons" }

        assertNull(
            spanData.attributes[GEN_AI_RESPONSE_FINISH_REASONS],
            "GEN_AI_RESPONSE_FINISH_REASONS should not be set for empty choices"
        )
    }

    @Test
    fun `handleResponseAttributes extracts service_tier to openai_response_service_tier`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            put("service_tier", "default")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-service-tier").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-service-tier" }

        assertEquals(
            "default",
            spanData.attributes[AttributeKey.stringKey("openai.response.service_tier")],
            "Expected openai.response.service_tier to be set"
        )
    }

    @Test
    fun `handleResponseAttributes does not set openai_response_service_tier when absent`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-no-service-tier").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-no-service-tier" }

        assertNull(
            spanData.attributes[AttributeKey.stringKey("openai.response.service_tier")],
            "openai.response.service_tier should not be set when absent"
        )
    }

    @Test
    fun `handleResponseAttributes extracts system_fingerprint to openai_response_system_fingerprint`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            put("system_fingerprint", "fp_abc123")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-system-fingerprint").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-system-fingerprint" }

        assertEquals(
            "fp_abc123",
            spanData.attributes[AttributeKey.stringKey("openai.response.system_fingerprint")],
            "Expected openai.response.system_fingerprint to be set"
        )
    }

    @Test
    fun `handleResponseAttributes does not set openai_response_system_fingerprint when absent`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-no-system-fingerprint").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-no-system-fingerprint" }

        assertNull(
            spanData.attributes[AttributeKey.stringKey("openai.response.system_fingerprint")],
            "openai.response.system_fingerprint should not be set when absent"
        )
    }

    @Test
    fun `handleResponseAttributes does not emit unmapped service_tier as tracy response attribute`() {
        val responseBody = buildJsonObject {
            put("id", "chatcmpl-123")
            put("model", "gpt-4o-mini")
            put("service_tier", "default")
            put("system_fingerprint", "fp_abc123")
            putJsonArray("choices") {}
        }

        val span = TracingManager.tracer.spanBuilder("test-no-unmapped-tier").startSpan()
        handler.handleResponseAttributes(span, makeResponse(responseBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-no-unmapped-tier" }

        assertNull(
            spanData.attributes[AttributeKey.stringKey("tracy.response.service_tier")],
            "service_tier must not leak as tracy.response.service_tier"
        )
        assertNull(
            spanData.attributes[AttributeKey.stringKey("tracy.response.system_fingerprint")],
            "system_fingerprint must not leak as tracy.response.system_fingerprint"
        )
    }
}
