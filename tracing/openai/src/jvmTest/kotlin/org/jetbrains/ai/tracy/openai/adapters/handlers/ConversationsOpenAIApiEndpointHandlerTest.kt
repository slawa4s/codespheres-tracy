/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import java.util.Optional
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * MockWebServer-based tests for the Conversations API endpoint handlers.
 * Each test covers one of the 8 Conversations routes and verifies the exact
 * span attributes produced.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_create_123"
            server.enqueueConversationResponse(id = conversationId)

            client.conversations().create(ConversationCreateParams.none())

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_retrieve_456"
            server.enqueueConversationResponse(id = conversationId)

            client.conversations().retrieve(
                ConversationRetrieveParams.builder().conversationId(conversationId).build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_update_789"
            server.enqueueConversationResponse(id = conversationId)

            client.conversations().update(
                ConversationUpdateParams.builder()
                    .conversationId(conversationId)
                    .metadata(Optional.empty())
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_delete_101"
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$conversationId","deleted":true,"object":"conversation.deleted"}""")
            )

            client.conversations().delete(
                ConversationDeleteParams.builder().conversationId(conversationId).build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    // ============ CREATE ITEMS: POST /conversations/{id}/items ============

    @Test
    fun `test CREATE ITEMS endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_items_202"
            val firstItemId = "msg_first_001"
            val lastItemId = "msg_last_002"
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [
                        {"id":"$firstItemId","type":"message","status":"completed","role":"user","content":[]},
                        {"id":"$lastItemId","type":"message","status":"completed","role":"assistant","content":[]}
                      ],
                      "first_id": "$firstItemId",
                      "last_id": "$lastItemId",
                      "has_more": false
                    }
                """.trimIndent())
            )

            val inputMessage = EasyInputMessage.builder()
                .content("Hello")
                .role(EasyInputMessage.Role.USER)
                .build()
            client.conversations().items().create(
                ItemCreateParams.builder()
                    .conversationId(conversationId)
                    .addItem(inputMessage)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
        }
    }

    // ============ LIST ITEMS: GET /conversations/{id}/items ============

    @Test
    fun `test LIST ITEMS endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_list_303"
            val firstItemId = "msg_list_001"
            val lastItemId = "msg_list_002"
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [
                        {"id":"$firstItemId","type":"message","status":"completed","role":"user","content":[]},
                        {"id":"$lastItemId","type":"message","status":"completed","role":"assistant","content":[]}
                      ],
                      "first_id": "$firstItemId",
                      "last_id": "$lastItemId",
                      "has_more": true
                    }
                """.trimIndent())
            )

            client.conversations().items().list(
                ItemListParams.builder()
                    .conversationId(conversationId)
                    .limit(10L)
                    .order(ItemListParams.Order.DESC)
                    .after("cursor_abc")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
            assertEquals("10", trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals("desc", trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals("cursor_abc", trace.attributes[AttributeKey.stringKey("tracy.request.after")])
        }
    }

    // ============ RETRIEVE ITEM: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test RETRIEVE ITEM endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_get_item_404"
            val itemId = "msg_retrieve_007"
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$itemId","type":"message","status":"completed","role":"user","content":[]}""")
            )

            client.conversations().items().retrieve(
                ItemRetrieveParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    // ============ DELETE ITEM: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `test DELETE ITEM endpoint attributes`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(server.url("/").toString(), MOCK_API_KEY)
                .apply { instrument(this) }

            val conversationId = "conv_del_item_505"
            val itemId = "msg_delete_008"
            val createdAt = Instant.now().epochSecond
            // delete item returns the updated Conversation object
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

            client.conversations().items().delete(
                ItemDeleteParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ HELPERS ============

    private fun MockWebServer.enqueueConversationResponse(
        id: String,
        createdAt: Long = Instant.now().epochSecond,
    ) {
        enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"id":"$id","object":"conversation","created_at":$createdAt}""")
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
