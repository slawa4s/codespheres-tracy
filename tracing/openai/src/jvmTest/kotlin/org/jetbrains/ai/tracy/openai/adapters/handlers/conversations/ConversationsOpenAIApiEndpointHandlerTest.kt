/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractConversationIdFromPath
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractItemIdFromPath
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler] using [okhttp3.MockWebServer].
 *
 * No real API keys are required — all HTTP responses are served by the mock server.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseAITracingTest() {

    private fun buildInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun jsonBody(json: String) =
        json.toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun conversationResponse(id: String = "conv-abc", obj: String = "conversation") =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"id":"$id","object":"$obj","created_at":1700000000}""")

    // ─── conversation lifecycle ────────────────────────────────────────────────

    @Test
    fun `create conversation - model attribute is recorded`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(conversationResponse())

            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .post(jsonBody("""{"model":"gpt-4o"}"""))
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("gpt-4o", span.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
            // gen_ai.operation.name comes from body["object"] via setCommonResponseAttributes
            assertEquals("conversation", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `retrieve conversation - conversation_id extracted from URL path`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(conversationResponse())

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc"))
                .get()
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
        }
    }

    @Test
    fun `delete conversation - deleted flag is recorded`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"conv-abc","deleted":true,"object":"conversation.deleted"}""")
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc"))
                .delete()
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals(true, span.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")])
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
        }
    }

    // ─── conversation items lifecycle ─────────────────────────────────────────

    @Test
    fun `create item - item type and role extracted from request body`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"item-xyz","object":"conversation.item","type":"message","role":"user"}"""
                    )
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc/items"))
                .post(jsonBody("""{"type":"message","role":"user","content":[]}"""))
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
            assertEquals("message", span.attributes[AttributeKey.stringKey("openai.conversation.item.type")])
            assertEquals("user", span.attributes[AttributeKey.stringKey("openai.conversation.item.role")])
            assertEquals("item-xyz", span.attributes[AttributeKey.stringKey("openai.conversation.item.id")])
        }
    }

    @Test
    fun `list items - pagination params and item count recorded`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"object":"list","data":[{"id":"item-1"},{"id":"item-2"}],"first_id":"item-1","last_id":"item-2","has_more":false}"""
                    )
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc/items?limit=10&order=asc"))
                .get()
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
            assertEquals("item-1", span.attributes[AttributeKey.stringKey("gen_ai.response.first_id")])
            assertEquals("item-2", span.attributes[AttributeKey.stringKey("gen_ai.response.last_id")])
            assertEquals(false, span.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")])
            assertEquals(2L, span.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
        }
    }

    @Test
    fun `retrieve item - conversation_id and item_id extracted from URL`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"item-xyz","object":"conversation.item","type":"message","role":"assistant"}"""
                    )
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc/items/item-xyz"))
                .get()
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
            assertEquals("item-xyz", span.attributes[AttributeKey.stringKey("openai.conversation.item.id")])
            assertEquals("message", span.attributes[AttributeKey.stringKey("openai.conversation.item.type")])
            assertEquals("assistant", span.attributes[AttributeKey.stringKey("openai.conversation.item.role")])
        }
    }

    @Test
    fun `delete item - item_id and deleted flag recorded`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"item-xyz","deleted":true,"object":"conversation.item.deleted"}""")
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv-abc/items/item-xyz"))
                .delete()
                .header("Authorization", "Bearer mock-key")
                .build()
            buildInstrumentedClient().newCall(request).execute().use {}

            val span = analyzeSpans().first()
            assertEquals("conv-abc", span.attributes[AttributeKey.stringKey("openai.conversation.id")])
            assertEquals("item-xyz", span.attributes[AttributeKey.stringKey("openai.conversation.item.id")])
            assertEquals(true, span.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")])
        }
    }

    // ─── path helper unit tests ───────────────────────────────────────────────

    @Test
    fun `extractConversationIdFromPath returns id from conversation path`() {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            pathSegments = listOf("v1", "conversations", "conv-abc"),
            parameters = emptyQueryParameters(),
        )
        assertEquals("conv-abc", extractConversationIdFromPath(url))
    }

    @Test
    fun `extractConversationIdFromPath returns null when path ends at conversations`() {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            pathSegments = listOf("v1", "conversations"),
            parameters = emptyQueryParameters(),
        )
        assertEquals(null, extractConversationIdFromPath(url))
    }

    @Test
    fun `extractItemIdFromPath returns id from item path`() {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            pathSegments = listOf("v1", "conversations", "conv-abc", "items", "item-xyz"),
            parameters = emptyQueryParameters(),
        )
        assertEquals("item-xyz", extractItemIdFromPath(url))
    }

    @Test
    fun `extractItemIdFromPath returns null when path ends at items`() {
        val url = TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            pathSegments = listOf("v1", "conversations", "conv-abc", "items"),
            parameters = emptyQueryParameters(),
        )
        assertEquals(null, extractItemIdFromPath(url))
    }

    private fun emptyQueryParameters() = object : TracyQueryParameters {
        override fun queryParameter(name: String): String? = null
        override fun queryParameterValues(name: String): List<String?> = emptyList()
    }
}
