/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * These tests use [MockWebServer] and a mock OpenAI API key, so they do not require access
 * to the real OpenAI Conversations API or any specific account configuration.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_abc123"
            server.enqueueConversationResponse(id = conversationId)

            client.conversations().create(ConversationCreateParams.none())

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.conversation.created_at")])
            assertEquals("conversation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test CREATE conversation failure gets traced with error status`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "error": {
                            "message": "Invalid request",
                            "type": "invalid_request_error",
                            "code": "invalid_params"
                        }
                    }
                """.trimIndent()))

            try {
                client.conversations().create(ConversationCreateParams.none())
            } catch (_: Exception) {
                // Expected
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_123"

            // CREATE first
            server.enqueueConversationResponse(id = conversationId)
            // RETRIEVE
            server.enqueueConversationResponse(id = conversationId)

            client.conversations().create(ConversationCreateParams.none())
            client.conversations().retrieve(conversationId)

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            // Verify conversation_id is traced from request path
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])

            // Verify response conversation object
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.conversation.created_at")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_update_456"

            server.enqueueConversationResponse(id = conversationId)

            val params = ConversationUpdateParams.builder()
                .conversationId(conversationId)
                .metadata(ConversationUpdateParams.Metadata.builder().build())
                .build()
            client.conversations().update(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_delete_789"

            // CREATE
            server.enqueueConversationResponse(id = conversationId)
            // DELETE
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "$conversationId",
                        "deleted": true,
                        "object": "conversation.deleted"
                    }
                """.trimIndent()))

            client.conversations().create(ConversationCreateParams.none())

            val deleteParams = ConversationDeleteParams.builder()
                .conversationId(conversationId)
                .build()
            val deleteResponse = client.conversations().delete(deleteParams)

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(deleteResponse.id(), trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")])
        }
    }

    // ============ CREATE_ITEMS: POST /conversations/{id}/items ============

    @Test
    fun `test CREATE_ITEMS endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_items_create_111"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "object": "list",
                        "data": [
                            {
                                "id": "item_abc",
                                "type": "message",
                                "status": "completed",
                                "role": "user",
                                "content": []
                            }
                        ],
                        "first_id": "item_abc",
                        "last_id": "item_abc",
                        "has_more": false
                    }
                """.trimIndent()))

            val createItemsParams = ItemCreateParams.builder()
                .conversationId(conversationId)
                .addItem(
                    EasyInputMessage.builder()
                        .content("Hello")
                        .role(EasyInputMessage.Role.USER)
                        .build()
                )
                .build()
            val itemList = client.conversations().items().create(createItemsParams)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(itemList.data().size.toLong(), trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertEquals("item_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.first_id")])
            assertEquals("item_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")])
        }
    }

    // ============ LIST_ITEMS: GET /conversations/{id}/items ============

    @Test
    fun `test LIST_ITEMS endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_222"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "object": "list",
                        "data": [
                            {
                                "id": "item_001",
                                "type": "message",
                                "status": "completed",
                                "role": "user",
                                "content": []
                            },
                            {
                                "id": "item_002",
                                "type": "message",
                                "status": "completed",
                                "role": "assistant",
                                "content": []
                            }
                        ],
                        "first_id": "item_001",
                        "last_id": "item_002",
                        "has_more": false
                    }
                """.trimIndent()))

            val listParams = ItemListParams.builder()
                .conversationId(conversationId)
                .build()
            val page = client.conversations().items().list(listParams)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(page.data().size.toLong(), trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertEquals("item_001", trace.attributes[AttributeKey.stringKey("gen_ai.response.first_id")])
            assertEquals("item_002", trace.attributes[AttributeKey.stringKey("gen_ai.response.last_id")])
            assertNotNull(trace.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")])
        }
    }

    @Test
    fun `test LIST_ITEMS pagination query parameters are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_333"
            val limit = 5L

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "object": "list",
                        "data": [],
                        "first_id": null,
                        "last_id": null,
                        "has_more": false
                    }
                """.trimIndent()))

            val listParams = ItemListParams.builder()
                .conversationId(conversationId)
                .limit(limit)
                .order(ItemListParams.Order.DESC)
                .build()
            client.conversations().items().list(listParams)

            val trace = analyzeSpans().first()

            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("gen_ai.request.limit")])
            assertEquals("desc", trace.attributes[AttributeKey.stringKey("gen_ai.request.order")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
        }
    }

    // ============ RETRIEVE_ITEM: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test RETRIEVE_ITEM endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(1)
            ).apply { instrument(this) }

            val conversationId = "conv_item_retrieve_444"
            val itemId = "item_retrieve_001"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                        "id": "$itemId",
                        "type": "message",
                        "status": "completed",
                        "role": "user",
                        "content": []
                    }
                """.trimIndent()))

            val retrieveParams = ItemRetrieveParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()
            client.conversations().items().retrieve(retrieveParams)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("gen_ai.request.item.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("gen_ai.response.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("gen_ai.response.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("gen_ai.response.item.status")])
        }
    }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueConversationResponse(
        id: String,
    ) {
        this.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {
                    "id": "$id",
                    "object": "conversation",
                    "created_at": ${Instant.now().epochSecond}
                }
            """.trimIndent()))
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
