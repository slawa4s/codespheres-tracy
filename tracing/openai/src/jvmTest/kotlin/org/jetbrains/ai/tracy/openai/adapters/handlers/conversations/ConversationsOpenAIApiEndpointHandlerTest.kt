/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * These tests do not call the real OpenAI API — a mock API key is used with a local server.
 */
@org.junit.jupiter.api.Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ Route: CREATE — POST /conversations ============

    @Test
    fun `conversationsHandler_routesToCreateOnPost`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "conv_abc123",
                            "object": "conversation",
                            "created_at": 1700000000,
                            "metadata": {}
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().create()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations",
                trace.attributes[AttributeKey.stringKey("openai.api.type")],
                "openai.api.type should be 'conversations' for POST /conversations"
            )
            assertEquals(
                "conv_abc123",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.id")],
                "gen_ai.response.id should be extracted from Conversation response"
            )
            assertEquals(
                1700000000L,
                trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")],
                "tracy.conversation.created_at should be extracted from Conversation response"
            )
        }
    }

    // ============ Route: ITEMS_LIST — GET /conversations/{id}/items ============

    @Test
    fun `conversationsHandler_routesToItemsListOnGetWithItems`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "object": "list",
                            "data": [],
                            "first_id": "item_first",
                            "last_id": "item_last",
                            "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = com.openai.models.conversations.items.ItemListParams.builder()
                .conversationId("conv_abc123")
                .build()
            client.conversations().items().list(params)

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations",
                trace.attributes[AttributeKey.stringKey("openai.api.type")],
                "openai.api.type should be 'conversations' for GET /conversations/{id}/items"
            )
            assertEquals(
                "conv_abc123",
                trace.attributes[AttributeKey.stringKey("tracy.conversation.requested_id")],
                "tracy.conversation.requested_id should be extracted from request URL"
            )
            assertEquals(
                "item_first",
                trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")],
                "tracy.conversation.items.first_id should be extracted from list response"
            )
            assertEquals(
                false,
                trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")],
                "tracy.conversation.items.has_more should be extracted from list response"
            )
        }
    }

    // ============ Route: ITEMS_DELETE — DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `conversationsHandler_routesToItemsDeleteOnDeleteWithItemId`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            // items.delete returns a Conversation object
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "conv_abc123",
                            "object": "conversation",
                            "created_at": 1700000000,
                            "metadata": {}
                        }
                        """.trimIndent()
                    )
            )

            val deleteParams = com.openai.models.conversations.items.ItemDeleteParams.builder()
                .conversationId("conv_abc123")
                .itemId("item_xyz999")
                .build()
            client.conversations().items().delete(deleteParams)

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations",
                trace.attributes[AttributeKey.stringKey("openai.api.type")],
                "openai.api.type should be 'conversations' for DELETE /conversations/{id}/items/{item_id}"
            )
            assertEquals(
                "conv_abc123",
                trace.attributes[AttributeKey.stringKey("tracy.conversation.requested_id")],
                "tracy.conversation.requested_id should be extracted from request URL"
            )
            assertEquals(
                "item_xyz999",
                trace.attributes[AttributeKey.stringKey("tracy.conversation.item.requested_id")],
                "tracy.conversation.item.requested_id should be extracted from request URL"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
