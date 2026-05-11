/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.http.protocol.*
import org.jetbrains.ai.tracy.core.instrument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [AnthropicBatchesEndpointHandler].
 *
 * All assertions target in-memory spans; no network calls are made and no real API keys
 * are required.
 */
@Tag("anthropic")
class AnthropicBatchesEndpointHandlerTest {

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
            .getTracer("batches-test")
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
            host = "api.anthropic.com",
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
            override val url = this@AnthropicBatchesEndpointHandlerTest.url(path)
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
        val handler = AnthropicBatchesEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request(path, method, requestJson))
        handler.handleResponseAttributes(span, response(path, method, responseJson))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── gen_ai.operation.name ─────────────────────────────────────────────────

    @Test
    fun `batches create sets operation name`() {
        val attrs = capture("/v1/messages/batches", "POST", requestJson = """{"requests":[]}""")
        assertEquals("batches.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches retrieve sets operation name`() {
        val attrs = capture("/v1/messages/batches/msgbatch_abc", "GET")
        assertEquals("batches.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches cancel sets operation name`() {
        val attrs = capture("/v1/messages/batches/msgbatch_abc/cancel", "POST")
        assertEquals("batches.cancel", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches results sets operation name`() {
        val attrs = capture("/v1/messages/batches/msgbatch_abc/results", "GET")
        assertEquals("batches.results", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches delete sets operation name`() {
        val attrs = capture("/v1/messages/batches/msgbatch_abc", "DELETE")
        assertEquals("batches.delete", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `batches delete sets output type to message_batch_deleted`() {
        val attrs = capture(
            "/v1/messages/batches/msgbatch_abc", "DELETE",
            responseJson = """{"id":"msgbatch_abc","deleted":true,"type":"message_batch_deleted"}"""
        )
        assertEquals("message_batch_deleted", attrs[AttributeKey.stringKey("anthropic.output.type")])
    }

    // ── anthropic.api.type ────────────────────────────────────────────────────

    @Test
    fun `every route sets anthropic api type to batches`() {
        val cases = listOf(
            Triple("/v1/messages/batches",              "POST", """{"requests":[]}"""),
            Triple("/v1/messages/batches/msgbatch_abc", "GET",  null),
            Triple("/v1/messages/batches/msgbatch_abc/cancel", "POST", null),
        )
        for ((path, method, reqBody) in cases) {
            val attrs = capture(path, method, requestJson = reqBody)
            assertEquals(
                "batches", attrs[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type must be 'batches' for $method $path"
            )
            spanExporter.reset()
        }
    }

    // ── anthropic.request.batch.size ─────────────────────────────────────────

    @Test
    fun `batches create records request batch size`() {
        val requestBody = """
            {
              "requests": [
                {"custom_id":"r1","params":{"model":"claude-3-5-haiku-20241022","max_tokens":10,"messages":[{"role":"user","content":"Hi"}]}},
                {"custom_id":"r2","params":{"model":"claude-3-5-haiku-20241022","max_tokens":10,"messages":[{"role":"user","content":"Bye"}]}}
              ]
            }
        """.trimIndent()
        val attrs = capture("/v1/messages/batches", "POST", requestJson = requestBody)
        assertEquals(2L, attrs[AttributeKey.longKey("anthropic.request.batch.size")])
    }

    @Test
    fun `batches retrieve does not set batch size`() {
        val attrs = capture("/v1/messages/batches/msgbatch_abc", "GET")
        assertEquals(null, attrs[AttributeKey.longKey("anthropic.request.batch.size")])
    }

    // ── anthropic.output.type ────────────────────────────────────────────────

    @Test
    fun `batch create sets output type to message_batch`() {
        val attrs = capture(
            "/v1/messages/batches", "POST",
            requestJson = """{"requests":[]}""",
            responseJson = """{"id":"msgbatch_x","processing_status":"in_progress","created_at":"2024-01-01T00:00:00Z","expires_at":"2024-01-02T00:00:00Z","request_counts":{"processing":0,"succeeded":0,"errored":0,"canceled":0,"expired":0}}"""
        )
        assertEquals("message_batch", attrs[AttributeKey.stringKey("anthropic.output.type")])
    }

    // ── anthropic.batch.* ────────────────────────────────────────────────────

    @Test
    fun `response attributes are parsed from MessageBatch object`() {
        val responseBody = """
            {
              "id": "msgbatch_013Zva2CMHLNnXjNJJKqJ2EF",
              "type": "message_batch",
              "processing_status": "ended",
              "created_at": "2024-09-24T18:37:24.100435Z",
              "expires_at": "2024-09-25T18:37:24.100435Z",
              "request_counts": {
                "processing": 0,
                "succeeded": 2,
                "errored": 1,
                "canceled": 0,
                "expired": 0
              }
            }
        """.trimIndent()
        val attrs = capture(
            "/v1/messages/batches/msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", "GET",
            responseJson = responseBody
        )
        assertEquals("msgbatch_013Zva2CMHLNnXjNJJKqJ2EF", attrs[AttributeKey.stringKey("anthropic.batch.id")])
        assertEquals("ended", attrs[AttributeKey.stringKey("anthropic.batch.processing_status")])
        assertEquals("2024-09-24T18:37:24.100435Z", attrs[AttributeKey.stringKey("anthropic.batch.created_at")])
        assertEquals("2024-09-25T18:37:24.100435Z", attrs[AttributeKey.stringKey("anthropic.batch.expires_at")])
        assertEquals(0L, attrs[AttributeKey.longKey("anthropic.batch.request_counts.processing")])
        assertEquals(2L, attrs[AttributeKey.longKey("anthropic.batch.request_counts.succeeded")])
        assertEquals(1L, attrs[AttributeKey.longKey("anthropic.batch.request_counts.errored")])
        assertEquals(0L, attrs[AttributeKey.longKey("anthropic.batch.request_counts.canceled")])
        assertEquals(0L, attrs[AttributeKey.longKey("anthropic.batch.request_counts.expired")])
    }

    // ── Error responses ───────────────────────────────────────────────────────

    @Test
    fun `batch error response does not set anthropic output type`() {
        val errorBody = """{"type":"error","error":{"type":"invalid_request_error","message":"request body is required"}}"""
        val handler = AnthropicBatchesEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request("/v1/messages/batches", "POST"))
        val errorResponse = object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 422
            override val body = TracyHttpResponseBody.Json(Json.parseToJsonElement(errorBody))
            override val url = url("/v1/messages/batches")
            override val requestMethod = "POST"
            override fun isError() = true
        }
        handler.handleResponseAttributes(span, errorResponse)
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertEquals(null, attrs[AttributeKey.stringKey("anthropic.output.type")])
    }

    // ── Distinct operation names (no collisions) ──────────────────────────────

    @Test
    fun `all five routes produce distinct operation names`() {
        val ops = mutableSetOf<String>()
        val cases = listOf(
            Triple("/v1/messages/batches",                      "POST",   """{"requests":[]}"""),
            Triple("/v1/messages/batches/msgbatch_abc",         "GET",    null),
            Triple("/v1/messages/batches/msgbatch_abc",         "DELETE", null),
            Triple("/v1/messages/batches/msgbatch_abc/cancel",  "POST",   null),
            Triple("/v1/messages/batches/msgbatch_abc/results", "GET",    null),
        )
        for ((path, method, reqBody) in cases) {
            val name = capture(path, method, requestJson = reqBody)[AttributeKey.stringKey("gen_ai.operation.name")]!!
            ops.add(name)
            spanExporter.reset()
        }
        assertEquals(5, ops.size, "All five batch routes must produce distinct operation names: $ops")
    }

    // ── Robustness: identifier attributes survive malformed bodies ───────────

    @Test
    fun `batches create with empty body still sets identifier attributes`() {
        val handler = AnthropicBatchesEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request("/v1/messages/batches", "POST", jsonBody = null))
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertEquals("batches.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("batches", attrs[AttributeKey.stringKey("anthropic.api.type")])
    }

    @Test
    fun `batches create with non-object body still sets identifier attributes`() {
        // A JSON array instead of object would cause .jsonObject to throw without runCatching
        val handler = AnthropicBatchesEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request("/v1/messages/batches", "POST", jsonBody = "[]"))
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertEquals("batches.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("batches", attrs[AttributeKey.stringKey("anthropic.api.type")])
    }

    // ── Full interceptor stack (MockWebServer) ────────────────────────────────

    /**
     * Exercises the full interceptor pipeline — [org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor]
     * → [AnthropicLLMTracingAdapter] → [AnthropicBatchesEndpointHandler] — for a 422 error response,
     * ensuring cross-cutting attributes (`gen_ai.provider.name`, `server.address`, `server.port`,
     * `http.response.status_code`, `error.type`) and the handler-specific attribute
     * (`anthropic.api.type`) are all recorded on the exported span.
     */
    @Test
    fun `batchesCreateWith422ResponseRecordsErrorSpan`() {
        val testExporter = InMemorySpanExporter.create()
        val testProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(testExporter))
            .build()
        TracingManager.setSdk(OpenTelemetrySdk.builder().setTracerProvider(testProvider).build())
        TracingManager.isTracingEnabled = true

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"requests must not be empty"}}""")
        )
        server.start()

        try {
            val client = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/messages/batches"))
                .post("""{"requests":[]}""".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use {}

            val spans = testExporter.finishedSpanItems
            assertEquals(1, spans.size, "Expected exactly one span for the batch request")
            val span = spans.first()

            assertEquals("anthropic", span.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals("batches", span.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals(422L, span.attributes[AttributeKey.longKey("http.response.status_code")])
            assertNotNull(span.attributes[AttributeKey.stringKey("error.type")], "error.type must be set on 422 response")
            assertNotNull(span.attributes[AttributeKey.stringKey("server.address")], "server.address must be set")
            assertNotNull(span.attributes[AttributeKey.longKey("server.port")], "server.port must be set")
        } finally {
            server.shutdown()
        }
    }
}
