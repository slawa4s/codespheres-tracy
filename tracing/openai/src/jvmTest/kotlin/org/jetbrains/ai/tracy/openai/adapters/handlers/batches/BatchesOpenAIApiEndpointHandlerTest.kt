/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [BatchesOpenAIApiEndpointHandler].
 *
 * All assertions target in-memory spans; no network calls are made and no real API keys
 * are required.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest {

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
            .getTracer("batches-openai-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun url(path: String): TracyHttpUrl {
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        return TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            port = 443,
            pathSegments = segments,
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String) = null
                override fun queryParameterValues(name: String) = emptyList<String?>()
            }
        )
    }

    private fun request(path: String, method: String, jsonBody: String? = null): TracyHttpRequest {
        val body = if (jsonBody != null)
            TracyHttpRequestBody.Json(Json.parseToJsonElement(jsonBody))
        else
            TracyHttpRequestBody.Empty
        return body.asRequestView(TracyContentType.Application.Json, url(path), method)
    }

    private fun response(path: String, method: String, jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@BatchesOpenAIApiEndpointHandlerTest.url(path)
            override val requestMethod = method.uppercase()
            override fun isError() = false
        }
    }

    private fun capture(
        path: String,
        method: String,
        requestJson: String? = null,
        responseJson: String = "{}",
    ): Attributes {
        val handler = BatchesOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request(path, method, requestJson))
        handler.handleResponseAttributes(span, response(path, method, responseJson))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── gen_ai.operation.name ─────────────────────────────────────────────────

    @Test
    fun `batches create sets operation name`() {
        val attrs = capture("/v1/batches", "POST", requestJson = """{"input_file_id":"file-abc","endpoint":"/v1/chat/completions","completion_window":"24h"}""")
        assertEquals("batches.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches list sets operation name`() {
        val attrs = capture("/v1/batches", "GET")
        assertEquals("batches.list", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches retrieve sets operation name`() {
        val attrs = capture("/v1/batches/batch_abc", "GET")
        assertEquals("batches.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches cancel sets operation name`() {
        val attrs = capture("/v1/batches/batch_abc/cancel", "POST")
        assertEquals("batches.cancel", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    // ── openai.api.type ───────────────────────────────────────────────────────

    @Test
    fun `every route sets openai api type to batches`() {
        val cases = listOf(
            Triple("/v1/batches",             "POST", """{"input_file_id":"f","endpoint":"/v1/chat/completions","completion_window":"24h"}"""),
            Triple("/v1/batches",             "GET",  null),
            Triple("/v1/batches/batch_abc",   "GET",  null),
            Triple("/v1/batches/batch_abc/cancel", "POST", null),
        )
        for ((path, method, reqBody) in cases) {
            val attrs = capture(path, method, requestJson = reqBody)
            assertEquals(
                "batches", attrs[AttributeKey.stringKey("openai.api.type")],
                "openai.api.type must be 'batches' for $method $path"
            )
            spanExporter.reset()
        }
    }

    // ── batches.create request attributes ────────────────────────────────────

    @Test
    fun `createBatchPopulatesRequestAndResponseAttributes`() {
        val requestBody = """
            {
              "input_file_id": "file-abc123",
              "endpoint": "/v1/chat/completions",
              "completion_window": "24h",
              "output_expires_after": {
                "anchor": "req_created_at",
                "seconds": 86400
              },
              "metadata": {
                "project": "my-project",
                "env": "staging"
              }
            }
        """.trimIndent()
        val responseBody = """
            {
              "id": "batch_abc123",
              "object": "batch",
              "endpoint": "/v1/chat/completions",
              "status": "validating",
              "input_file_id": "file-abc123",
              "completion_window": "24h",
              "created_at": 1711471533,
              "request_counts": {
                "total": 10,
                "completed": 0,
                "failed": 0
              }
            }
        """.trimIndent()

        val attrs = capture("/v1/batches", "POST", requestJson = requestBody, responseJson = responseBody)

        // openai.api.type
        assertEquals("batches", attrs[AttributeKey.stringKey("openai.api.type")])
        // gen_ai.operation.name
        assertEquals("batches.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])

        // request batch attributes
        assertEquals("/v1/chat/completions", attrs[AttributeKey.stringKey("tracy.request.batch.endpoint")])
        assertEquals("24h", attrs[AttributeKey.stringKey("tracy.request.batch.completion_window")])
        assertEquals("req_created_at", attrs[AttributeKey.stringKey("tracy.request.batch.output_expires_after.anchor")])
        assertEquals(86400L, attrs[AttributeKey.longKey("tracy.request.batch.output_expires_after.seconds")])
        assertEquals("file-abc123", attrs[AttributeKey.stringKey("tracy.request.batch.input_file.id")])
        assertEquals("env,project", attrs[AttributeKey.stringKey("tracy.request.metadata.keys")])

        // response batch attributes
        assertEquals("batch_abc123", attrs[AttributeKey.stringKey("tracy.batch.id")])
        assertEquals("validating", attrs[AttributeKey.stringKey("tracy.batch.status")])
        assertEquals(1711471533L, attrs[AttributeKey.longKey("tracy.batch.created_at")])
        assertEquals(10L, attrs[AttributeKey.longKey("tracy.batch.request_counts.total")])
        assertEquals(0L, attrs[AttributeKey.longKey("tracy.batch.request_counts.completed")])
        assertEquals(0L, attrs[AttributeKey.longKey("tracy.batch.request_counts.failed")])
    }

    @Test
    fun `batches create with no metadata does not set metadata keys`() {
        val attrs = capture(
            "/v1/batches", "POST",
            requestJson = """{"input_file_id":"file-x","endpoint":"/v1/chat/completions","completion_window":"24h"}"""
        )
        assertNull(attrs[AttributeKey.stringKey("tracy.request.metadata.keys")])
    }

    @Test
    fun `batches retrieve does not set request batch attributes`() {
        val attrs = capture("/v1/batches/batch_abc", "GET")
        assertNull(attrs[AttributeKey.stringKey("tracy.request.batch.endpoint")])
        assertNull(attrs[AttributeKey.stringKey("tracy.request.batch.input_file.id")])
    }

    // ── Response attributes ───────────────────────────────────────────────────

    @Test
    fun `retrieve response populates batch response attributes`() {
        val responseBody = """
            {
              "id": "batch_xyz",
              "object": "batch",
              "status": "completed",
              "created_at": 1711471000,
              "request_counts": {
                "total": 5,
                "completed": 4,
                "failed": 1
              }
            }
        """.trimIndent()
        val attrs = capture("/v1/batches/batch_xyz", "GET", responseJson = responseBody)

        assertEquals("batches.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("batch_xyz", attrs[AttributeKey.stringKey("tracy.batch.id")])
        assertEquals("completed", attrs[AttributeKey.stringKey("tracy.batch.status")])
        assertEquals(1711471000L, attrs[AttributeKey.longKey("tracy.batch.created_at")])
        assertEquals(5L, attrs[AttributeKey.longKey("tracy.batch.request_counts.total")])
        assertEquals(4L, attrs[AttributeKey.longKey("tracy.batch.request_counts.completed")])
        assertEquals(1L, attrs[AttributeKey.longKey("tracy.batch.request_counts.failed")])
    }

    // ── Distinct operation names (no collisions) ──────────────────────────────

    @Test
    fun `all four routes produce distinct operation names`() {
        val ops = mutableSetOf<String>()
        val cases = listOf(
            Triple("/v1/batches",                  "POST", """{"input_file_id":"f","endpoint":"/v1/chat/completions","completion_window":"24h"}"""),
            Triple("/v1/batches",                  "GET",  null),
            Triple("/v1/batches/batch_abc",        "GET",  null),
            Triple("/v1/batches/batch_abc/cancel", "POST", null),
        )
        for ((path, method, reqBody) in cases) {
            val name = capture(path, method, requestJson = reqBody)[AttributeKey.stringKey("gen_ai.operation.name")]!!
            ops.add(name)
            spanExporter.reset()
        }
        assertEquals(4, ops.size, "All four batch routes must produce distinct operation names: $ops")
    }
}
