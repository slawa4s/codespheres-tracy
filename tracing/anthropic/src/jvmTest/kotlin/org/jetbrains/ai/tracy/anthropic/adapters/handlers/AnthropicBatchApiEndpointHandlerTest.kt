/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.test.utils.BaseOpenTelemetryTracingTest
import io.opentelemetry.api.common.AttributeKey
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnthropicBatchApiEndpointHandlerTest : BaseOpenTelemetryTracingTest() {
    private val handler = AnthropicBatchApiEndpointHandler()

    // ──────────────────────────────────────────────────────────────────────────
    // detectOperation helper tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `detectOperation returns create for POST to batches`() {
        assertEquals("create", AnthropicBatchApiEndpointHandler.detectOperation("POST", listOf("v1", "messages", "batches")))
    }

    @Test
    fun `detectOperation returns list for GET to batches`() {
        assertEquals("list", AnthropicBatchApiEndpointHandler.detectOperation("GET", listOf("v1", "messages", "batches")))
    }

    @Test
    fun `detectOperation returns retrieve for GET to batches with id`() {
        assertEquals("retrieve", AnthropicBatchApiEndpointHandler.detectOperation("GET", listOf("v1", "messages", "batches", "msgbatch_abc123")))
    }

    @Test
    fun `detectOperation returns cancel for POST to batches cancel`() {
        assertEquals("cancel", AnthropicBatchApiEndpointHandler.detectOperation("POST", listOf("v1", "messages", "batches", "msgbatch_abc123", "cancel")))
    }

    @Test
    fun `detectOperation returns unknown when batches segment is absent`() {
        assertEquals("unknown", AnthropicBatchApiEndpointHandler.detectOperation("GET", listOf("v1", "messages")))
    }

    @Test
    fun `detectOperation ignores trailing empty path segments`() {
        assertEquals("create", AnthropicBatchApiEndpointHandler.detectOperation("POST", listOf("v1", "messages", "batches", "")))
        assertEquals("list", AnthropicBatchApiEndpointHandler.detectOperation("GET", listOf("v1", "messages", "batches", "")))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // handleRequestAttributes
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `create operation sets gen_ai_operation_name and batch size`() {
        val body = """{"requests": [{}, {}, {}]}"""
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleRequestAttributes(span, mockRequest("POST", listOf("v1", "messages", "batches"), body))
        } finally {
            span.end()
        }

        val spans = analyzeSpans()
        assertEquals(1, spans.size)
        val attrs = spans.first().attributes
        assertEquals("create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals(3L, attrs[AttributeKey.longKey("gen_ai.request.batch.size")])
    }

    @Test
    fun `list operation sets gen_ai_operation_name without batch size`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleRequestAttributes(span, mockRequest("GET", listOf("v1", "messages", "batches")))
        } finally {
            span.end()
        }

        val spans = analyzeSpans()
        val attrs = spans.first().attributes
        assertEquals("list", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertNull(attrs[AttributeKey.longKey("gen_ai.request.batch.size")], "list should not set batch size")
    }

    @Test
    fun `retrieve operation sets gen_ai_operation_name`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleRequestAttributes(span, mockRequest("GET", listOf("v1", "messages", "batches", "msgbatch_abc")))
        } finally {
            span.end()
        }

        assertEquals("retrieve", analyzeSpans().first().attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `cancel operation sets gen_ai_operation_name`() {
        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleRequestAttributes(span, mockRequest("POST", listOf("v1", "messages", "batches", "msgbatch_abc", "cancel")))
        } finally {
            span.end()
        }

        assertEquals("cancel", analyzeSpans().first().attributes[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    // ──────────────────────────────────────────────────────────────────────────
    // handleResponseAttributes — single batch (create / retrieve / cancel)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `single batch response maps all batch attributes`() {
        val responseBody = """
            {
                "id": "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
                "type": "message_batch",
                "processing_status": "ended",
                "created_at": "2024-10-15T14:30:00Z",
                "expires_at": "2024-10-16T14:30:00Z",
                "request_counts": {
                    "processing": 0,
                    "succeeded": 100,
                    "errored": 30,
                    "canceled": 10,
                    "expired": 5
                }
            }
        """.trimIndent()

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleResponseAttributes(span, mockResponse("POST", listOf("v1", "messages", "batches"), responseBody))
        } finally {
            span.end()
        }

        val attrs = analyzeSpans().first().attributes
        assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", attrs[AttributeKey.stringKey("gen_ai.response.batch.id")])
        assertEquals("ended", attrs[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        assertEquals("2024-10-15T14:30:00Z", attrs[AttributeKey.stringKey("gen_ai.response.batch.created_at")])
        assertEquals("2024-10-16T14:30:00Z", attrs[AttributeKey.stringKey("gen_ai.response.batch.expires_at")])
        assertEquals(0L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
        assertEquals(100L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.succeeded")])
        assertEquals(30L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.errored")])
        assertEquals(10L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.canceled")])
        assertEquals(5L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.expired")])
    }

    @Test
    fun `retrieve response maps batch attributes`() {
        val responseBody = """
            {
                "id": "msgbatch_retrieve123",
                "processing_status": "in_progress",
                "created_at": "2024-10-15T12:00:00Z",
                "expires_at": "2024-10-16T12:00:00Z",
                "request_counts": {
                    "processing": 50,
                    "succeeded": 0,
                    "errored": 0,
                    "canceled": 0,
                    "expired": 0
                }
            }
        """.trimIndent()

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleResponseAttributes(span, mockResponse("GET", listOf("v1", "messages", "batches", "msgbatch_retrieve123"), responseBody))
        } finally {
            span.end()
        }

        val attrs = analyzeSpans().first().attributes
        assertEquals("msgbatch_retrieve123", attrs[AttributeKey.stringKey("gen_ai.response.batch.id")])
        assertEquals("in_progress", attrs[AttributeKey.stringKey("gen_ai.response.batch.processing_status")])
        assertEquals(50L, attrs[AttributeKey.longKey("gen_ai.response.batch.request_counts.processing")])
    }

    // ──────────────────────────────────────────────────────────────────────────
    // handleResponseAttributes — list
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `list response maps all list attributes`() {
        val responseBody = """
            {
                "data": [
                    {"id": "msgbatch_001"},
                    {"id": "msgbatch_002"},
                    {"id": "msgbatch_003"}
                ],
                "has_more": true,
                "first_id": "msgbatch_001",
                "last_id": "msgbatch_003"
            }
        """.trimIndent()

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleResponseAttributes(span, mockResponse("GET", listOf("v1", "messages", "batches"), responseBody))
        } finally {
            span.end()
        }

        val attrs = analyzeSpans().first().attributes
        assertEquals(3L, attrs[AttributeKey.longKey("gen_ai.response.list.count")])
        assertEquals(true, attrs[AttributeKey.booleanKey("gen_ai.response.list.has_more")])
        assertEquals("msgbatch_001", attrs[AttributeKey.stringKey("gen_ai.response.list.first_id")])
        assertEquals("msgbatch_003", attrs[AttributeKey.stringKey("gen_ai.response.list.last_id")])
    }

    @Test
    fun `list response does not set batch attributes`() {
        val responseBody = """
            {
                "data": [],
                "has_more": false,
                "first_id": null,
                "last_id": null
            }
        """.trimIndent()

        val span = TracingManager.tracer.spanBuilder("test").startSpan()
        try {
            handler.handleResponseAttributes(span, mockResponse("GET", listOf("v1", "messages", "batches"), responseBody))
        } finally {
            span.end()
        }

        val attrs = analyzeSpans().first().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.response.batch.id")], "list response must not set batch.id")
        assertNull(attrs[AttributeKey.stringKey("gen_ai.response.batch.processing_status")], "list response must not set batch.processing_status")
        assertEquals(0L, attrs[AttributeKey.longKey("gen_ai.response.list.count")])
        assertEquals(false, attrs[AttributeKey.booleanKey("gen_ai.response.list.has_more")])
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun mockUrl(pathSegments: List<String>): TracyHttpUrl = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.anthropic.com",
        pathSegments = pathSegments,
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String) = null
            override fun queryParameterValues(name: String) = emptyList<String?>()
        }
    )

    private fun mockRequest(
        method: String,
        pathSegments: List<String>,
        jsonBody: String? = null,
    ): TracyHttpRequest {
        val body: TracyHttpRequestBody = if (jsonBody != null) {
            TracyHttpRequestBody.Json(Json.parseToJsonElement(jsonBody))
        } else {
            TracyHttpRequestBody.Empty
        }
        val url = mockUrl(pathSegments)
        return object : TracyHttpRequest {
            override val method = method
            override val url = url
            override val contentType: TracyContentType = TracyContentType.Application.Json
            override val body = body
        }
    }

    private fun mockResponse(
        requestMethod: String,
        pathSegments: List<String>,
        jsonBody: String,
        statusCode: Int = 200,
    ): TracyHttpResponse {
        val parsedBody = TracyHttpResponseBody.Json(Json.parseToJsonElement(jsonBody))
        val url = mockUrl(pathSegments)
        return object : TracyHttpResponse {
            override val requestMethod = requestMethod
            override val url = url
            override val contentType: TracyContentType = TracyContentType.Application.Json
            override val code = statusCode
            override val body = parsedBody
            override fun isError() = statusCode >= 400
        }
    }
}
