/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock API key, so they do not require access to the real
 * OpenAI Conversations API.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation - operation name and attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_create_123"
            val createdAt = Instant.now().epochSecond

            server.enqueueConversationResponse(id = convId, createdAt = createdAt)

            client.conversations().create(ConversationCreateParams.builder().build())

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation - operation name and id are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_retrieve_456"
            val createdAt = Instant.now().epochSecond

            // First create, then retrieve
            server.enqueueConversationResponse(id = convId, createdAt = createdAt)
            server.enqueueConversationResponse(id = convId, createdAt = createdAt)

            client.conversations().create(ConversationCreateParams.builder().build())
            client.conversations().retrieve(convId)

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation - operation name and attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_update_789"
            val createdAt = Instant.now().epochSecond

            server.enqueueConversationResponse(id = convId, createdAt = createdAt)
            server.enqueueConversationResponse(id = convId, createdAt = createdAt)

            client.conversations().create(ConversationCreateParams.builder().build())
            client.conversations().update(
                ConversationUpdateParams.builder()
                    .conversationId(convId)
                    .metadata(ConversationUpdateParams.Metadata.builder().build())
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation - operation name and deleted flag are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_delete_321"

            server.enqueueConversationResponse(id = convId)
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$convId","deleted":true,"object":"conversation.deleted"}"""))

            client.conversations().create(ConversationCreateParams.builder().build())
            client.conversations().delete(convId)

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    // ============ ITEMS_CREATE: POST /conversations/{id}/items ============

    @Test
    fun `test ITEMS CREATE - operation name and list attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_items_create_111"
            val itemId = "item_create_aaa"

            server.enqueueConversationResponse(id = convId)
            server.enqueueItemListResponse(
                conversationId = convId,
                itemIds = listOf(itemId),
                firstId = itemId,
                lastId = itemId,
                hasMore = false
            )

            client.conversations().create(ConversationCreateParams.builder().build())
            client.conversations().items().create(
                ItemCreateParams.builder()
                    .conversationId(convId)
                    .addItem(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content("Hello")
                            .build()
                    )
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            val trace = traces.last()

            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ ITEMS_LIST: GET /conversations/{id}/items ============

    @Test
    fun `test ITEMS LIST - operation name and list attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_items_list_222"
            val firstItemId = "item_list_bbb"
            val lastItemId = "item_list_ccc"

            server.enqueueItemListResponse(
                conversationId = convId,
                itemIds = listOf(firstItemId, lastItemId),
                firstId = firstItemId,
                lastId = lastItemId,
                hasMore = false
            )

            client.conversations().items().list(convId)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastItemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    @Test
    fun `test ITEMS LIST - pagination query parameters are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_items_page_333"
            val afterCursor = "item_cursor_ddd"
            val limit = 10L
            val order = "desc"

            server.enqueueItemListResponse(
                conversationId = convId,
                itemIds = emptyList(),
                firstId = null,
                lastId = null,
                hasMore = true
            )

            client.conversations().items().list(
                ItemListParams.builder()
                    .conversationId(convId)
                    .after(afterCursor)
                    .limit(limit)
                    .order(ItemListParams.Order.DESC)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(afterCursor, trace.attributes[AttributeKey.stringKey("tracy.request.after")])
            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ ITEMS_RETRIEVE: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS RETRIEVE - operation name and item attributes are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_item_retrieve_444"
            val itemId = "item_retrieve_eee"
            val itemType = "message"
            val itemStatus = "completed"

            server.enqueueItemResponse(id = itemId, type = itemType, status = itemStatus)

            client.conversations().items().retrieve(
                ItemRetrieveParams.builder()
                    .conversationId(convId)
                    .itemId(itemId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals(itemType, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals(itemStatus, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    // ============ ITEMS_DELETE: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS DELETE - operation name and item id are traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val convId = "conv_item_delete_555"
            val itemId = "item_delete_fff"
            val createdAt = Instant.now().epochSecond

            // items().delete returns the updated Conversation object
            server.enqueueConversationResponse(id = convId, createdAt = createdAt)

            client.conversations().items().delete(
                ItemDeleteParams.builder()
                    .conversationId(convId)
                    .itemId(itemId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueConversationResponse(
        id: String,
        createdAt: Long = Instant.now().epochSecond,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","created_at":$createdAt,"object":"conversation"}""")
        )
    }

    private fun MockWebServer.enqueueItemListResponse(
        conversationId: String,
        itemIds: List<String>,
        firstId: String?,
        lastId: String?,
        hasMore: Boolean,
    ) {
        val itemsJson = itemIds.joinToString(",") { id ->
            """{"id":"$id","type":"message","status":"completed","role":"user","content":[]}"""
        }
        val firstIdJson = if (firstId != null) """"first_id":"$firstId"""" else """"first_id":null"""
        val lastIdJson = if (lastId != null) """"last_id":"$lastId"""" else """"last_id":null"""
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[$itemsJson],$firstIdJson,$lastIdJson,"has_more":$hasMore}"""
                )
        )
    }

    private fun MockWebServer.enqueueItemResponse(
        id: String,
        type: String = "message",
        status: String = "completed",
        role: String = "user",
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","type":"$type","status":"$status","role":"$role","content":[]}"""
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
