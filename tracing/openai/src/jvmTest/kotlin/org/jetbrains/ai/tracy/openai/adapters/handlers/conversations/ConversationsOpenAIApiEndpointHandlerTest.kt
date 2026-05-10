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
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * These tests do not require a real OpenAI API key or network access.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private val jsonContentType = "application/json".toMediaType()

    // ===== openai.api.type and gen_ai.operation.name on all routes =====

    @Test
    fun `conversations create sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueConversationResponse(id = "conv_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post("""{"model":"gpt-4o"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations retrieve sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueConversationResponse(id = "conv_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations update sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueConversationResponse(id = "conv_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc"))
                    .post("""{"metadata":{}}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations delete sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueDeleteResponse(id = "conv_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations items create sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemResponse(id = "item_xyz")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items"))
                    .post("""{"type":"message","role":"user"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations items list sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemListResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations items retrieve sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemResponse(id = "item_xyz")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items/item_xyz"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `conversations items delete sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueDeleteResponse(id = "item_xyz")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items/item_xyz"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ===== Per-route attribute extraction =====

    @Test
    fun `conversations create extracts model and response id`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueConversationResponse(id = "conv_new123")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post("""{"model":"gpt-4o-mini"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("gpt-4o-mini", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("conv_new123", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `conversations retrieve traces conversation id`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueConversationResponse(id = "conv_abc")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conv_abc", trace.attributes[AttributeKey.stringKey("tracy.conversation.id")])
            assertEquals("conv_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    @Test
    fun `conversations delete traces id and deletion status`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueDeleteResponse(id = "conv_del")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_del"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conv_del", trace.attributes[AttributeKey.stringKey("tracy.conversation.id")])
            assertEquals("conv_del", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")] == true)
        }
    }

    @Test
    fun `conversations items create traces type and role`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemResponse(id = "item_001")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items"))
                    .post("""{"type":"message","role":"user"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conv_abc", trace.attributes[AttributeKey.stringKey("tracy.conversation.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("user", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.role")])
            assertEquals("item_001", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
        }
    }

    @Test
    fun `conversations items list traces item count and has_more`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemListResponse(count = 3, hasMore = true)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.conversation.items_count")])
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")] == true)
        }
    }

    @Test
    fun `conversations items retrieve traces conversation and item ids`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueItemResponse(id = "item_xyz", type = "message", role = "assistant")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items/item_xyz"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conv_abc", trace.attributes[AttributeKey.stringKey("tracy.conversation.id")])
            assertEquals("item_xyz", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("item_xyz", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("assistant", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.role")])
        }
    }

    @Test
    fun `conversations items delete traces conversation and item ids plus deletion status`() = runTest {
        withMockServer { server ->
            val client = buildClient(server)
            server.enqueueDeleteResponse(id = "item_del")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc/items/item_del"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("conv_abc", trace.attributes[AttributeKey.stringKey("tracy.conversation.id")])
            assertEquals("item_del", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("item_del", trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")] == true)
        }
    }

    // ===== Helpers =====

    private fun buildClient(server: MockWebServer): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun MockWebServer.enqueueConversationResponse(id: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","object":"conversation","created_at":1700000000}"""
                )
        )
    }

    private fun MockWebServer.enqueueDeleteResponse(id: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","deleted":true,"object":"conversation.deleted"}"""
                )
        )
    }

    private fun MockWebServer.enqueueItemResponse(
        id: String,
        type: String = "message",
        role: String = "user",
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","object":"conversation.item","type":"$type","role":"$role"}"""
                )
        )
    }

    private fun MockWebServer.enqueueItemListResponse(count: Int = 2, hasMore: Boolean = false) {
        val items = (1..count).joinToString(",") {
            """{"id":"item_$it","object":"conversation.item","type":"message","role":"user"}"""
        }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[$items],"has_more":$hasMore}""")
        )
    }

}
