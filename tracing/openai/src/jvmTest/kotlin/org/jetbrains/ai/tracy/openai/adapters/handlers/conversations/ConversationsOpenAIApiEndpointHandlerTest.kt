/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
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

/**
 * Unit tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * All assertions are made against attributes recorded on in-memory spans; no network calls
 * are made and no real API keys are required.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest {

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
            .getTracer("conversations-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds a [TracyHttpUrl] from a raw path, with optional query parameters. */
    private fun url(path: String, queryParams: Map<String, String> = emptyMap()): TracyHttpUrl {
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        return TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            pathSegments = segments,
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String) = queryParams[name]
                override fun queryParameterValues(name: String) =
                    queryParams[name]?.let { listOf(it) } ?: emptyList()
            }
        )
    }

    /** Minimal [TracyHttpRequest] for the given path/method (no body). */
    private fun request(
        path: String,
        method: String,
        queryParams: Map<String, String> = emptyMap(),
    ): TracyHttpRequest =
        TracyHttpRequestBody.Empty.asRequestView(null, url(path, queryParams), method)

    /** [TracyHttpResponse] backed by a JSON string. */
    private fun response(path: String, method: String, jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@ConversationsOpenAIApiEndpointHandlerTest.url(path)
            override val requestMethod = method.uppercase()
            override fun isError() = false
        }
    }

    /**
     * Runs the handler for a single request/response pair and returns the resulting span attributes.
     */
    private fun capture(
        path: String,
        method: String,
        responseJson: String,
        queryParams: Map<String, String> = emptyMap(),
    ): Attributes {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleRequestAttributes(span, request(path, method, queryParams))
        handler.handleResponseAttributes(span, response(path, method, responseJson))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── gen_ai.operation.name ─────────────────────────────────────────────────

    @Test
    fun `conversations create sets operation name`() {
        val attrs = capture(
            "/v1/conversations", "POST",
            """{"id":"conv_1","object":"conversation","created_at":1710000000}"""
        )
        assertEquals("conversations.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations retrieve sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1", "GET",
            """{"id":"conv_1","object":"conversation","created_at":1710000000}"""
        )
        assertEquals("conversations.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations update sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1", "PATCH",
            """{"id":"conv_1","object":"conversation","created_at":1710000000}"""
        )
        assertEquals("conversations.update", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations delete sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1", "DELETE",
            """{"id":"conv_1","deleted":true,"object":"conversation.deleted"}"""
        )
        assertEquals("conversations.delete", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations items create sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1/items", "POST",
            """{"data":[],"first_id":null,"last_id":null,"has_more":false}"""
        )
        assertEquals("conversations.items.create", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations items list sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1/items", "GET",
            """{"data":[],"first_id":null,"last_id":null,"has_more":false}"""
        )
        assertEquals("conversations.items.list", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations items retrieve sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1/items/item_1", "GET",
            """{"id":"item_1","type":"message","status":"completed"}"""
        )
        assertEquals("conversations.items.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `conversations items delete sets operation name`() {
        val attrs = capture(
            "/v1/conversations/conv_1/items/item_1", "DELETE",
            """{"id":"item_1","type":"message","status":"completed"}"""
        )
        assertEquals("conversations.items.delete", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    // ── openai.api.type ───────────────────────────────────────────────────────

    @Test
    fun `every route sets openai api type to conversations`() {
        val cases = listOf(
            Triple("/v1/conversations", "POST",
                """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c", "GET",
                """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c", "PATCH",
                """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c", "DELETE",
                """{"id":"c","deleted":true,"object":"conversation.deleted"}"""),
            Triple("/v1/conversations/c/items", "POST",
                """{"data":[],"has_more":false}"""),
            Triple("/v1/conversations/c/items", "GET",
                """{"data":[],"has_more":false}"""),
            Triple("/v1/conversations/c/items/i", "GET",
                """{"id":"i","type":"message","status":"completed"}"""),
            Triple("/v1/conversations/c/items/i", "DELETE",
                """{"id":"i","type":"message","status":"completed"}"""),
        )
        for ((path, method, body) in cases) {
            val attrs = capture(path, method, body)
            assertEquals(
                "conversations", attrs[AttributeKey.stringKey("openai.api.type")],
                "openai.api.type must be 'conversations' for $method $path"
            )
            spanExporter.reset()
        }
    }

    // ── Conversation-level attributes ─────────────────────────────────────────

    @Test
    fun `conversations create extracts id and created_at`() {
        val attrs = capture(
            "/v1/conversations", "POST",
            """{"id":"conv_abc","object":"conversation","created_at":1710000100}"""
        )
        assertEquals("conv_abc", attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals(1710000100L, attrs[AttributeKey.longKey("tracy.conversation.created_at")])
    }

    @Test
    fun `conversations retrieve extracts id and created_at`() {
        val attrs = capture(
            "/v1/conversations/conv_abc", "GET",
            """{"id":"conv_abc","object":"conversation","created_at":1710000200}"""
        )
        assertEquals("conv_abc", attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals(1710000200L, attrs[AttributeKey.longKey("tracy.conversation.created_at")])
    }

    @Test
    fun `conversations delete extracts id and deleted flag`() {
        val attrs = capture(
            "/v1/conversations/conv_abc", "DELETE",
            """{"id":"conv_abc","deleted":true,"object":"conversation.deleted"}"""
        )
        assertEquals("conv_abc", attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals(true, attrs[AttributeKey.booleanKey("tracy.conversation.deleted")])
    }

    // ── Items-collection attributes ───────────────────────────────────────────

    @Test
    fun `items list extracts conversation id from URL and list metadata`() {
        val attrs = capture(
            "/v1/conversations/conv_xyz/items", "GET",
            """
            {
              "data": [{"id":"item_1"},{"id":"item_2"}],
              "first_id": "item_1",
              "last_id":  "item_2",
              "has_more": false
            }
            """.trimIndent()
        )
        assertEquals("conv_xyz", attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals(2L,         attrs[AttributeKey.longKey("tracy.conversation.items.count")])
        assertEquals("item_1",   attrs[AttributeKey.stringKey("tracy.conversation.items.first_id")])
        assertEquals("item_2",   attrs[AttributeKey.stringKey("tracy.conversation.items.last_id")])
        assertEquals(false,      attrs[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
    }

    @Test
    fun `items list reads query parameters limit order after`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("qp-test").startSpan()
        handler.handleRequestAttributes(
            span,
            request(
                "/v1/conversations/conv_1/items", "GET",
                queryParams = mapOf("limit" to "20", "order" to "asc", "after" to "item_99")
            )
        )
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes

        assertEquals("20",      attrs[AttributeKey.stringKey("tracy.request.limit")])
        assertEquals("asc",     attrs[AttributeKey.stringKey("tracy.request.order")])
        assertEquals("item_99", attrs[AttributeKey.stringKey("tracy.request.after")])
    }

    // ── Single-item attributes ────────────────────────────────────────────────

    @Test
    fun `item retrieve extracts conversation id from URL and item fields`() {
        val attrs = capture(
            "/v1/conversations/conv_xyz/items/item_42", "GET",
            """{"id":"item_42","type":"message","status":"completed"}"""
        )
        assertEquals("conv_xyz",   attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals("item_42",    attrs[AttributeKey.stringKey("tracy.conversation.item.id")])
        assertEquals("message",    attrs[AttributeKey.stringKey("tracy.conversation.item.type")])
        assertEquals("completed",  attrs[AttributeKey.stringKey("tracy.conversation.item.status")])
    }

    @Test
    fun `item delete extracts conversation id from URL and item fields`() {
        val attrs = capture(
            "/v1/conversations/conv_xyz/items/item_42", "DELETE",
            """{"id":"item_42","type":"message","status":"deleted"}"""
        )
        assertEquals("conv_xyz",  attrs[AttributeKey.stringKey("gen_ai.conversation.id")])
        assertEquals("item_42",   attrs[AttributeKey.stringKey("tracy.conversation.item.id")])
        assertEquals("deleted",   attrs[AttributeKey.stringKey("tracy.conversation.item.status")])
    }

    // ── Distinct operation names (no collisions) ──────────────────────────────

    @Test
    fun `all eight routes produce distinct operation names`() {
        val opNames = mutableSetOf<String>()
        val cases = listOf(
            Triple("/v1/conversations",           "POST",   """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c",         "GET",    """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c",         "PATCH",  """{"id":"c","object":"conversation","created_at":1}"""),
            Triple("/v1/conversations/c",         "DELETE", """{"id":"c","deleted":true,"object":"conversation.deleted"}"""),
            Triple("/v1/conversations/c/items",   "POST",   """{"data":[],"has_more":false}"""),
            Triple("/v1/conversations/c/items",   "GET",    """{"data":[],"has_more":false}"""),
            Triple("/v1/conversations/c/items/i", "GET",    """{"id":"i","type":"message","status":"x"}"""),
            Triple("/v1/conversations/c/items/i", "DELETE", """{"id":"i","type":"message","status":"x"}"""),
        )
        for ((path, method, body) in cases) {
            val name = capture(path, method, body)[AttributeKey.stringKey("gen_ai.operation.name")]!!
            opNames.add(name)
            spanExporter.reset()
        }
        assertEquals(8, opNames.size, "All eight routes must produce distinct operation names: $opNames")
    }
}
