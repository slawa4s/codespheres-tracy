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
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [GeminiContentGenHandler].
 *
 * These tests use in-process span capture and do **not** require a live API key
 * or LLM provider.
 */
class GeminiContentGenHandlerTest : BaseAITracingTest() {

    private fun generateContentUrl(model: String = "gemini-2.5-flash") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "models", "$model:generateContent"),
        parameters = emptyQueryParameters(),
    )

    private fun streamGenerateContentUrl(model: String = "gemini-2.5-flash") = TracyHttpUrlImpl(
        scheme = "https",
        host = "generativelanguage.googleapis.com",
        port = 443,
        pathSegments = listOf("v1beta", "models", "$model:streamGenerateContent"),
        parameters = emptyQueryParameters(),
    )

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }

    private fun makeRequest(url: TracyHttpUrl, body: kotlinx.serialization.json.JsonObject): TracyHttpRequest =
        object : TracyHttpRequest {
            override val contentType = TracyContentType.Application.Json
            override val body = TracyHttpRequestBody.Json(body)
            override val url = url
            override val method = "POST"
        }

    private fun handler() = GeminiContentGenHandler(MediaContentExtractorImpl())

    @Test
    fun `generateContent request sets gemini api type to models`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler().handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "hello") })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `streamGenerateContent request sets gemini api type to models`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        handler().handleRequestAttributes(span, makeRequest(streamGenerateContentUrl(), buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "hello") })
                    }
                })
            }
        }))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }

    @Test
    fun `generateContent request sets gemini api type even when body has no contents`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()

        // Body without "contents" — handler should still set the attribute before parsing
        handler().handleRequestAttributes(span, makeRequest(generateContentUrl(), buildJsonObject {}))
        span.end()

        val spanData = analyzeSpans().single { it.name == "test" }
        assertEquals("models", spanData.attributes[AttributeKey.stringKey("gemini.api.type")])
    }
}
