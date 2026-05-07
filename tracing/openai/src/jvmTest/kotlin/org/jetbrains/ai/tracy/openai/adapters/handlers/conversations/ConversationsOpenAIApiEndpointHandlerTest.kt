/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.core.JsonValue
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import io.opentelemetry.api.common.AttributeKey
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
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock API key, so they do not require access to the real
 * OpenAI Conversations API.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /v1/conversations ============

    @Test
    fun `test CREATE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_abc123"
            server.enqueueConversationResponse(conversationId)

            val conversation = client.conversations().create()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversation.id(), trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.conversation.created_at")])
            assertEquals(
                "create_conversation",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    @Test
    fun `test CREATE conversation failure gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "error": {
                                "message": "Bad request",
                                "type": "invalid_request_error",
                                "code": "invalid_request"
                            }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.conversations().create()
            } catch (_: Exception) {
                // expected
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
        }
    }

    // ============ RETRIEVE: GET /v1/conversations/{conversation_id} ============

    @Test
    fun `test RETRIEVE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_456"
            server.enqueueConversationResponse(conversationId)

            client.conversations().retrieve(
                ConversationRetrieveParams.builder()
                    .conversationId(conversationId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals(
                "retrieve_conversation",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ UPDATE: POST /v1/conversations/{conversation_id} ============

    @Test
    fun `test UPDATE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_update_789"
            server.enqueueConversationResponse(conversationId)

            client.conversations().update(
                ConversationUpdateParams.builder()
                    .conversationId(conversationId)
                    .metadata(
                        ConversationUpdateParams.Metadata.builder()
                            .putAdditionalProperty("key", JsonValue.from("value"))
                            .build()
                    )
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals(
                "update_conversation",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ DELETE: DELETE /v1/conversations/{conversation_id} ============

    @Test
    fun `test DELETE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
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
                          "object": "conversation.deleted",
                          "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            val deleteResult = client.conversations().delete(
                ConversationDeleteParams.builder()
                    .conversationId(conversationId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(deleteResult.id(), trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")])
            assertEquals(
                "delete_conversation",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ ITEM_CREATE: POST /v1/conversations/{conversation_id}/items ============

    @Test
    fun `test ITEM CREATE endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_items_123"
            val itemId = "item_msg_001"

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
                              "id": "$itemId",
                              "type": "message",
                              "object": "realtime.item",
                              "role": "user",
                              "status": "completed"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )

            val item = ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .content(EasyInputMessage.Content.ofTextInput("Hello, conversations!"))
                    .role(EasyInputMessage.Role.USER)
                    .build()
            )

            client.conversations().items().create(
                ItemCreateParams.builder()
                    .conversationId(conversationId)
                    .items(listOf(item))
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("gen_ai.response.items.0.id")])
            assertEquals(
                "create_conversation_items",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ ITEM_LIST: GET /v1/conversations/{conversation_id}/items ============

    @Test
    fun `test ITEM LIST endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_list_456"

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
                              "id": "item_001",
                              "type": "message",
                              "object": "realtime.item",
                              "role": "user",
                              "status": "completed"
                            },
                            {
                              "id": "item_002",
                              "type": "message",
                              "object": "realtime.item",
                              "role": "assistant",
                              "status": "completed"
                            }
                          ],
                          "has_more": false,
                          "first_id": "item_001",
                          "last_id": "item_002"
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().items().list(
                ItemListParams.builder()
                    .conversationId(conversationId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertFalse(trace.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")] == true)
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.items.0.id")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.items.1.id")])
            assertEquals(
                "list_conversation_items",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ ITEM_RETRIEVE: GET /v1/conversations/{conversation_id}/items/{item_id} ============

    @Test
    fun `test ITEM RETRIEVE endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_item_789"
            val itemId = "item_retrieve_001"

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
                              "id": "$itemId",
                              "type": "message",
                              "object": "realtime.item",
                              "role": "user",
                              "status": "completed"
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().items().retrieve(
                ItemRetrieveParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("gen_ai.request.item.id")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertEquals(
                "retrieve_conversation_item",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ ITEM_DELETE: DELETE /v1/conversations/{conversation_id}/items/{item_id} ============

    @Test
    fun `test ITEM DELETE endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_delete_item_abc"
            val itemId = "item_delete_001"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$conversationId",
                          "object": "realtime.conversation",
                          "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().items().delete(
                ItemDeleteParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("gen_ai.request.item.id")])
            assertEquals(
                "delete_conversation_item",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ MULTIPLE CALLS ============

    @Test
    fun `test multiple conversation operations produce separate traces`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val conversationId = "conv_multi_ops"

            // Enqueue CREATE response
            server.enqueueConversationResponse(conversationId)
            // Enqueue RETRIEVE response
            server.enqueueConversationResponse(conversationId)

            client.conversations().create()
            client.conversations().retrieve(
                ConversationRetrieveParams.builder()
                    .conversationId(conversationId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)

            val createTrace = traces[0]
            val retrieveTrace = traces[1]

            assertEquals(
                "create_conversation",
                createTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(
                "retrieve_conversation",
                retrieveTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
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
                .setBody(
                    """
                    {
                      "id": "$id",
                      "object": "realtime.conversation",
                      "created_at": $createdAt
                    }
                    """.trimIndent()
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
