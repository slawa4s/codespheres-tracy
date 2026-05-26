/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContent
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChatCompletionsRequestAttributesTest : BaseOpenTelemetryTracingTest() {

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

    private fun makeRequest(body: JsonObject): TracyHttpRequest {
        return object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Json(body)
            override val url: TracyHttpUrl = testUrl
            override val method = "POST"
        }
    }

    @Test
    fun `max_completion_tokens maps to GEN_AI_REQUEST_MAX_TOKENS`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("max_completion_tokens", 32)
        }

        val span = TracingManager.tracer.spanBuilder("test-max-completion-tokens").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-max-completion-tokens" }

        assertEquals(32L, spanData.attributes[GEN_AI_REQUEST_MAX_TOKENS])
        assertNull(
            spanData.attributes[AttributeKey.stringKey("tracy.request.max_completion_tokens")],
            "max_completion_tokens should not appear as unmapped attribute"
        )
    }

    @Test
    fun `max_tokens fallback maps to GEN_AI_REQUEST_MAX_TOKENS when max_completion_tokens absent`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("max_tokens", 64)
        }

        val span = TracingManager.tracer.spanBuilder("test-max-tokens-fallback").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-max-tokens-fallback" }

        assertEquals(64L, spanData.attributes[GEN_AI_REQUEST_MAX_TOKENS])
        assertNull(
            spanData.attributes[AttributeKey.stringKey("tracy.request.max_tokens")],
            "max_tokens should not appear as unmapped attribute"
        )
    }

    @Test
    fun `max_completion_tokens takes priority over max_tokens`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("max_completion_tokens", 32)
            put("max_tokens", 64)
        }

        val span = TracingManager.tracer.spanBuilder("test-max-tokens-priority").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-max-tokens-priority" }

        assertEquals(32L, spanData.attributes[GEN_AI_REQUEST_MAX_TOKENS])
    }

    @Test
    fun `tool_choice string auto sets tracy request tool_choice without extra quotes`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("tool_choice", "auto")
        }

        val span = TracingManager.tracer.spanBuilder("test-tool-choice-auto").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-tool-choice-auto" }

        assertEquals(
            "auto",
            spanData.attributes[AttributeKey.stringKey("tracy.request.tool_choice")],
            "tool_choice should be 'auto' without surrounding quotes"
        )
    }

    @Test
    fun `tool_choice object sets tracy request tool_choice as JSON string`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "get_weather")
                }
            }
        }

        val span = TracingManager.tracer.spanBuilder("test-tool-choice-object").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-tool-choice-object" }

        val toolChoiceAttr = spanData.attributes[AttributeKey.stringKey("tracy.request.tool_choice")]
        assertNotNull(toolChoiceAttr, "tracy.request.tool_choice should be set for object tool_choice")
        assert(toolChoiceAttr.contains("get_weather")) {
            "tool_choice JSON string should contain function name, got: $toolChoiceAttr"
        }
    }

    @Test
    fun `tool_choice sets tracy request tool_choice not in unmapped attributes`() {
        val requestBody = buildJsonObject {
            put("model", "gpt-4o-mini")
            put("tool_choice", "none")
        }

        val span = TracingManager.tracer.spanBuilder("test-tool-choice-not-unmapped").startSpan()
        handler.handleRequestAttributes(span, makeRequest(requestBody))
        span.end()

        val spans = analyzeSpans()
        val spanData = spans.single { it.name == "test-tool-choice-not-unmapped" }

        assertEquals("none", spanData.attributes[AttributeKey.stringKey("tracy.request.tool_choice")])
    }
}
