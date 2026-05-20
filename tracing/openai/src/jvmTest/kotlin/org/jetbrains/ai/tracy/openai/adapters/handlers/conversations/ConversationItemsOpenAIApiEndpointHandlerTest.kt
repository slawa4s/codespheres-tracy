/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.policy.ContentCapturePolicy
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for the four `/conversations/{id}/items` routes. The companion suite for the
 * top-level Conversations CRUD lives in [ConversationsOpenAIApiEndpointHandlerTest].
 */
@Tag("openai")
class ConversationItemsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ ITEMS_CREATE: POST /conversations/{id}/items ============

    @Test
    fun `test ITEMS_CREATE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_create_001"

            server.enqueue(enqueueConversationItemListResponse(count = 1))

            val params = ItemCreateParams.builder()
                .conversationId(conversationId)
                .addItem(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("Hello")
                        .build()
                )
                .build()
            client.conversations().items().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.list.count")])
            assertNotNull(trace.attributes[AttributeKey.booleanKey("tracy.response.list.has_more")])
        }
    }

    @Test
    fun `test ITEMS_CREATE request items are redacted under default policy`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = false, captureOutputs = false)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueConversationItemListResponse(count = 1))

            val params = ItemCreateParams.builder()
                .conversationId("conv_redacted_items")
                .addItem(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("secret request")
                        .build()
                )
                .build()
            client.conversations().items().create(params)

            val trace = analyzeSpans().first()

            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.request.items.count")])
            assertEquals("user", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.role")])
            assertEquals("REDACTED", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.content")])
        }
    }

    // ============ ITEMS_LIST: GET /conversations/{id}/items ============

    @Test
    fun `test ITEMS_LIST endpoint gets traced with pagination attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_002"
            val limit = 5L
            val order = "desc"
            val after = "msg_abc123"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "object": "list",
                          "data": [
                            {
                              "id": "msg_item_1",
                              "type": "message",
                              "status": "completed",
                              "role": "user",
                              "content": []
                            }
                          ],
                          "first_id": "msg_item_1",
                          "last_id": "msg_item_1",
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .limit(limit)
                .order(ItemListParams.Order.DESC)
                .after(after)
                .build()
            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])

            // Verify pagination request params are traced
            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(after, trace.attributes[AttributeKey.stringKey("tracy.request.after")])

            // Verify response list metadata + per-element item id
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.list.count")])
            assertEquals("list", trace.attributes[AttributeKey.stringKey("tracy.response.list.object")])
            assertEquals("msg_item_1", trace.attributes[AttributeKey.stringKey("tracy.response.list.first_id")])
            assertEquals("msg_item_1", trace.attributes[AttributeKey.stringKey("tracy.response.list.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.response.list.has_more")])
            assertEquals("msg_item_1", trace.attributes[AttributeKey.stringKey("tracy.response.data.0.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.response.data.0.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.response.data.0.status")])
            assertEquals("user", trace.attributes[AttributeKey.stringKey("tracy.response.data.0.role")])
        }
    }

    @Test
    fun `test ITEMS_LIST endpoint with empty list gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_empty"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "object": "list",
                          "data": [],
                          "first_id": null,
                          "last_id": null,
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.response.list.count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.response.list.has_more")])
        }
    }

    // ============ ITEMS_RETRIEVE: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_RETRIEVE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_item_retrieve_003"
            val itemId = "msg_retrieve_xyz"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$itemId",
                          "type": "message",
                          "status": "completed",
                          "role": "assistant",
                          "content": []
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemRetrieveParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()
            client.conversations().items().retrieve(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.request.item_id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.response.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.response.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.response.status")])
            assertEquals("assistant", trace.attributes[AttributeKey.stringKey("tracy.response.role")])
        }
    }

    // ============ ITEMS_DELETE: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_DELETE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_item_delete_004"
            val itemId = "msg_delete_abc"

            // ITEMS_DELETE returns the parent Conversation object
            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ItemDeleteParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()
            client.conversations().items().delete(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.request.item_id")])
            // Response is the parent Conversation
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.response.id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.created_at")])
        }
    }

    // ============ Lifecycle ============

    @Test
    fun `test conversations items lifecycle - create items then list`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_lifecycle_002"

            server.enqueue(enqueueConversationItemListResponse(count = 1))
            server.enqueue(enqueueConversationItemListResponse(count = 1))

            // Create items
            client.conversations().items().create(
                ItemCreateParams.builder()
                    .conversationId(conversationId)
                    .addItem(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content("Hello")
                            .build()
                    )
                    .build()
            )
            // List items
            client.conversations().items().list(
                ItemListParams.builder().conversationId(conversationId).build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)

            assertEquals("conversations.items.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.items.list", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueConversationResponse(
        id: String,
        createdAt: Long = Instant.now().epochSecond
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "conversation",
                  "created_at": $createdAt,
                  "metadata": {}
                }
                """.trimIndent()
            )
    }

    private fun enqueueConversationItemListResponse(
        count: Int,
        firstId: String = "msg_first_001",
        lastId: String = "msg_last_001",
        hasMore: Boolean = false
    ): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """
            {
              "id": "msg_item_$i",
              "type": "message",
              "status": "completed",
              "role": "user",
              "content": []
            }
            """.trimIndent()
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "object": "list",
                  "data": [$items],
                  "first_id": "$firstId",
                  "last_id": "$lastId",
                  "has_more": $hasMore
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
