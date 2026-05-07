/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.core.JsonValue
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
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val conversationId = "conv_create_123"

            server.enqueueConversationResponse(conversationId)

            val conversation = client.conversations().create()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversation.id(), trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.conversation.created_at")])
            assertEquals("conversation", trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.object")])
            assertEquals("create_conversation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test CREATE conversation failure gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "error": {
                                "message": "Invalid request",
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
                timeout = Duration.ofSeconds(30)
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

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.requested_id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals("retrieve_conversation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.requested_id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals("update_conversation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
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

            client.conversations().delete(
                ConversationDeleteParams.builder()
                    .conversationId(conversationId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.requested_id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.response.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.deleted")])
            assertEquals("delete_conversation", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ LIST_ITEMS: GET /conversations/{id}/items ============

    @Test
    fun `test LIST_ITEMS endpoint gets traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            val conversationId = "conv_list_items_xyz"

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
                                    "object": "conversation.item",
                                    "type": "message"
                                },
                                {
                                    "id": "item_002",
                                    "object": "conversation.item",
                                    "type": "message"
                                }
                            ],
                            "has_more": false
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

            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.request.conversation.requested_id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.items_count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("gen_ai.response.has_more")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.items.0.id")])
            assertEquals("list_items", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueConversationResponse(id: String) {
        this.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "id": "$id",
                        "object": "conversation",
                        "created_at": ${Instant.now().epochSecond}
                    }
                    """.trimIndent()
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
