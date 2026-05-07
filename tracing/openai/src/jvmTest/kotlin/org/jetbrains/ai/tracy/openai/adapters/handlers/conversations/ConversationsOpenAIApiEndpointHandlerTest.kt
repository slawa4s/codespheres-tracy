/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock API key so they do not
 * require access to the real OpenAI Conversations API.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation - operation name and attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_create_123"
                val createdAt = Instant.now().epochSecond

                server.enqueueConversationResponse(conversationId, createdAt)

                client.conversations().create()

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.create",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertEquals(
                    createdAt,
                    trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")]
                )
            }
        }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation - operation name and attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_retrieve_456"
                val createdAt = Instant.now().epochSecond

                server.enqueueConversationResponse(conversationId, createdAt)

                client.conversations().retrieve(
                    ConversationRetrieveParams.builder()
                        .conversationId(conversationId)
                        .build()
                )

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.retrieve",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
            }
        }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation - operation name and attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_update_789"
                val createdAt = Instant.now().epochSecond

                server.enqueueConversationResponse(conversationId, createdAt)

                client.conversations().update(
                    ConversationUpdateParams.builder()
                        .conversationId(conversationId)
                        .build()
                )

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.update",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
            }
        }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation - operation name and attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_delete_abc"

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "id": "$conversationId",
                              "deleted": true,
                              "object": "conversation.deleted"
                            }
                            """.trimIndent()
                        )
                )

                client.conversations().delete(
                    ConversationDeleteParams.builder()
                        .conversationId(conversationId)
                        .build()
                )

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.delete",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertEquals(
                    true,
                    trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")]
                )
            }
        }

    // ============ ITEMS_CREATE: POST /conversations/{id}/items ============

    @Test
    fun `test ITEMS_CREATE - operation name and item list attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_items_create_111"
                val firstItemId = "item_first_001"
                val lastItemId = "item_last_002"

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
                                  "id": "$firstItemId",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "user",
                                  "content": [{"type": "input_text", "text": "Hello"}]
                                },
                                {
                                  "id": "$lastItemId",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "assistant",
                                  "content": [{"type": "output_text", "text": "Hi there"}]
                                }
                              ],
                              "first_id": "$firstItemId",
                              "last_id": "$lastItemId",
                              "has_more": false
                            }
                            """.trimIndent()
                        )
                )

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

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.items.create",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertEquals(
                    2L,
                    trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")]
                )
                assertEquals(
                    firstItemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")]
                )
                assertEquals(
                    lastItemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")]
                )
                assertEquals(
                    false,
                    trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")]
                )
            }
        }

    // ============ ITEMS_LIST: GET /conversations/{id}/items ============

    @Test
    fun `test ITEMS_LIST - operation name, pagination params, and item list attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_items_list_222"
                val firstItemId = "item_page_001"
                val lastItemId = "item_page_003"
                val limit = 3L
                val order = "desc"

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
                                  "id": "$firstItemId",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "user"
                                },
                                {
                                  "id": "item_page_002",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "assistant"
                                },
                                {
                                  "id": "$lastItemId",
                                  "type": "message",
                                  "status": "completed",
                                  "role": "user"
                                }
                              ],
                              "first_id": "$firstItemId",
                              "last_id": "$lastItemId",
                              "has_more": true
                            }
                            """.trimIndent()
                        )
                )

                val params = ItemListParams.builder()
                    .conversationId(conversationId)
                    .limit(limit)
                    .order(ItemListParams.Order.DESC)
                    .build()

                client.conversations().items().list(params)

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.items.list",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                // Query parameters
                assertEquals(
                    limit.toString(),
                    trace.attributes[AttributeKey.stringKey("tracy.request.limit")]
                )
                assertEquals(
                    order,
                    trace.attributes[AttributeKey.stringKey("tracy.request.order")]
                )
                // Item list response attributes
                assertEquals(
                    3L,
                    trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")]
                )
                assertEquals(
                    firstItemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")]
                )
                assertEquals(
                    lastItemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")]
                )
                assertEquals(
                    true,
                    trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")]
                )
            }
        }

    @Test
    fun `test ITEMS_LIST - after cursor query param is traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_items_list_after_333"
                val after = "item_cursor_xyz"

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
                    .after(after)
                    .build()

                client.conversations().items().list(params)

                val trace = analyzeSpans().first()

                assertEquals(
                    after,
                    trace.attributes[AttributeKey.stringKey("tracy.request.after")]
                )
                assertEquals(
                    0L,
                    trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")]
                )
            }
        }

    // ============ ITEMS_RETRIEVE: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_RETRIEVE - operation name and item attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_item_retrieve_444"
                val itemId = "item_retrieve_abc"
                val itemType = "message"
                val itemStatus = "completed"

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """
                            {
                              "id": "$itemId",
                              "type": "$itemType",
                              "status": "$itemStatus",
                              "role": "assistant",
                              "content": [{"type": "output_text", "text": "Hello world"}]
                            }
                            """.trimIndent()
                        )
                )

                val params = ItemRetrieveParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()

                client.conversations().items().retrieve(params)

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.items.retrieve",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertEquals(
                    itemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")]
                )
                assertEquals(
                    itemType,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")]
                )
                assertEquals(
                    itemStatus,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")]
                )
                // request-side item ID attribute
                assertTrue(
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")] != null,
                    "tracy.conversation.item.id should be set from both request URL and response body"
                )
            }
        }

    // ============ ITEMS_DELETE: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_DELETE - operation name and parent conversation attributes are traced`() =
        runTest(timeout = 3.minutes) {
            withMockServer { server ->
                val client = createOpenAIClient(
                    url = server.url("/").toString(),
                    apiKey = MOCK_API_KEY,
                    timeout = Duration.ofMinutes(3)
                ).apply { instrument(this) }

                val conversationId = "conv_item_delete_555"
                val itemId = "item_delete_xyz"
                val parentCreatedAt = Instant.now().epochSecond

                // ITEMS_DELETE returns the parent conversation
                server.enqueueConversationResponse(conversationId, parentCreatedAt)

                val params = ItemDeleteParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()

                client.conversations().items().delete(params)

                val trace = analyzeSpans().first()

                assertEquals(
                    "conversations.items.delete",
                    trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
                )
                assertEquals(
                    "conversations",
                    trace.attributes[AttributeKey.stringKey("openai.api.type")]
                )
                // Response body is parent conversation; its id → gen_ai.conversation.id
                assertEquals(
                    conversationId,
                    trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
                )
                assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
                // Request-side item.id should also be set
                assertEquals(
                    itemId,
                    trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")]
                )
            }
        }

    // ============ HELPER METHODS ============

    /**
     * Enqueues a mock response with a [Conversation] JSON body.
     */
    private fun okhttp3.mockwebserver.MockWebServer.enqueueConversationResponse(
        id: String,
        createdAt: Long = Instant.now().epochSecond,
    ) {
        enqueue(
            MockResponse()
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
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
