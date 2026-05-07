/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import io.opentelemetry.api.common.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for batch URL detection and attribute delegation in [AnthropicLLMTracingAdapter].
 *
 * These tests exercise the adapter directly (no real HTTP calls) by supplying
 * synthetic [TracyHttpRequest] / [TracyHttpResponse] objects pointing at
 * `/v1/messages/batches` and verifying that:
 *  - `anthropic.api.type = "batches"` is emitted for batch URLs.
 *  - `gen_ai.operation.name = "create_message_batch"` is emitted by the batch handler.
 *  - Non-batch URLs do **not** set `anthropic.api.type`.
 *  - Batch response attributes (id, processing_status, request_counts) are recorded.
 *  - An empty/null batch body does not throw.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicBatchesTracingAdapterTest : BaseOpenTelemetryTracingTest() {

    private val adapter = AnthropicLLMTracingAdapter()

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun batchUrl(suffix: String = ""): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.anthropic.com",
        pathSegments = listOf("v1", "messages", "batches") + suffix.split("/").filter { it.isNotEmpty() },
        parameters = NoOpQueryParameters
    )

    private fun messagesUrl(): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.anthropic.com",
        pathSegments = listOf("v1", "messages"),
        parameters = NoOpQueryParameters
    )

    private fun jsonRequestBody(json: String): TracyHttpRequestBody =
        TracyHttpRequestBody.Json(Json.parseToJsonElement(json))

    private fun jsonResponseBody(json: String): TracyHttpResponseBody =
        TracyHttpResponseBody.Json(Json.parseToJsonElement(json))

    private fun makeRequest(url: TracyHttpUrl, bodyJson: String? = null): TracyHttpRequest {
        val body = if (bodyJson != null) jsonRequestBody(bodyJson) else TracyHttpRequestBody.Empty
        return body.asRequestView(contentType = TracyContentType.Application.Json, url = url, method = "POST")
    }

    private fun makeResponse(url: TracyHttpUrl, bodyJson: String, code: Int = 200): TracyHttpResponse {
        val responseBody = jsonResponseBody(bodyJson)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = code
            override val body = responseBody
            override val url = url
            override val requestMethod = "POST"
            override fun isError() = code >= 400
        }
    }

    private fun runSpan(block: (span: io.opentelemetry.api.trace.Span) -> Unit): io.opentelemetry.sdk.trace.data.SpanData {
        val span = TracingManager.tracer.spanBuilder("test-batch").startSpan()
        try {
            block(span)
        } finally {
            span.end()
        }
        return analyzeSpans().last()
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `batch URL sets anthropic api type attribute`() {
        val request = makeRequest(
            url = batchUrl(),
            bodyJson = """{"requests":[{"custom_id":"r1","params":{"model":"claude-opus-4-5","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}}]}"""
        )

        val span = runSpan { adapter.registerRequest(it, request) }

        assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
    }

    @Test
    fun `batch URL sets gen_ai operation name`() {
        val request = makeRequest(
            url = batchUrl(),
            bodyJson = """{"requests":[{"custom_id":"r1","params":{"model":"claude-opus-4-5","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}}]}"""
        )

        val span = runSpan { adapter.registerRequest(it, request) }

        assertEquals("create_message_batch", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batch URL extracts model from first request params`() {
        val request = makeRequest(
            url = batchUrl(),
            bodyJson = """{"requests":[{"custom_id":"r1","params":{"model":"claude-opus-4-5","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}}]}"""
        )

        val span = runSpan { adapter.registerRequest(it, request) }

        assertEquals("claude-opus-4-5", span.attributes[AttributeKey.stringKey("gen_ai.request.model")])
    }

    @Test
    fun `non-batch URL does not set anthropic api type`() {
        val request = makeRequest(
            url = messagesUrl(),
            bodyJson = """{"model":"claude-opus-4-5","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}"""
        )

        val span = runSpan { adapter.registerRequest(it, request) }

        assertNull(span.attributes[AttributeKey.stringKey("anthropic.api.type")])
    }

    @Test
    fun `batch response extracts id and processing status`() {
        val response = makeResponse(
            url = batchUrl(),
            bodyJson = """
                {
                  "id": "msgbatch_abc123",
                  "type": "message_batch",
                  "processing_status": "in_progress",
                  "request_counts": {
                    "processing": 3,
                    "succeeded": 0,
                    "errored": 0,
                    "canceled": 0,
                    "expired": 0
                  }
                }
            """.trimIndent()
        )

        val span = runSpan { adapter.registerResponse(it, response) }

        assertEquals("msgbatch_abc123", span.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        assertEquals("in_progress", span.attributes[AttributeKey.stringKey("anthropic.batch.processing_status")])
    }

    @Test
    fun `batch response extracts request counts`() {
        val response = makeResponse(
            url = batchUrl(),
            bodyJson = """
                {
                  "id": "msgbatch_abc123",
                  "type": "message_batch",
                  "processing_status": "ended",
                  "request_counts": {
                    "processing": 0,
                    "succeeded": 5,
                    "errored": 2,
                    "canceled": 1,
                    "expired": 0
                  }
                }
            """.trimIndent()
        )

        val span = runSpan { adapter.registerResponse(it, response) }

        assertEquals(0L, span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.processing")])
        assertEquals(5L, span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.succeeded")])
        assertEquals(2L, span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.errored")])
        assertEquals(1L, span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.canceled")])
        assertEquals(0L, span.attributes[AttributeKey.longKey("anthropic.batch.request_counts.expired")])
    }

    @Test
    fun `batch request with empty body does not throw`() {
        val request = makeRequest(url = batchUrl(), bodyJson = null)

        // Should complete without exception even with an empty body
        val span = runSpan { adapter.registerRequest(it, request) }

        assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
        assertEquals("create_message_batch", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batch sub-resource URL (results) is also detected as batch`() {
        // GET /v1/messages/batches/{id}/results
        val url = batchUrl("msgbatch_abc123/results")
        val request = makeRequest(url = url, bodyJson = null)

        val span = runSpan { adapter.registerRequest(it, request) }

        assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
    }

    // ── no-op query parameters implementation for tests ────────────────────────

    private object NoOpQueryParameters : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }
}
