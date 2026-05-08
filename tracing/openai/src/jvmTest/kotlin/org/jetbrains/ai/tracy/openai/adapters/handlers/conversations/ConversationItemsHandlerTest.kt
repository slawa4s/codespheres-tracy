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
import com.openai.models.responses.ResponseInputItem
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for conversation items span attributes. All tests use [MockWebServer] and a mock API key
 * — no real OpenAI API calls are made.
 */
@Tag("openai")
class ConversationItemsHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations/{conv_id}/items ============

    @Test
    fun `create items sets operation name and conversation id`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_abc123"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [
                        {"id": "item_001", "type": "message", "status": "completed"},
                        {"id": "item_002", "type": "message", "status": "completed"}
                      ],
                      "first_id": "item_001",
                      "last_id": "item_002"
                    }
                """.trimIndent()))

            val item = ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .content("Hello")
                    .role(EasyInputMessage.Role.USER)
                    .build()
            )
            val params = ItemCreateParams.builder()
                .conversationId(conversationId)
                .addItem(item)
                .build()

            client.conversations().items().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `create items response reads list metadata`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val firstId = "item_first"
            val lastId = "item_last"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [
                        {"id": "$firstId", "type": "message", "status": "completed"},
                        {"id": "$lastId", "type": "message", "status": "completed"}
                      ],
                      "first_id": "$firstId",
                      "last_id": "$lastId"
                    }
                """.trimIndent()))

            val item = ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .content("Test")
                    .role(EasyInputMessage.Role.USER)
                    .build()
            )
            val params = ItemCreateParams.builder()
                .conversationId("conv_test")
                .addItem(item)
                .build()

            client.conversations().items().create(params)

            val trace = analyzeSpans().first()

            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
        }
    }

    // ============ LIST: GET /conversations/{conv_id}/items ============

    @Test
    fun `list items sets operation name and conversation id`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_list_test"

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

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .build()

            client.conversations().items().list(params)

            val trace = analyzeSpans().first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `list items reads pagination query parameters`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val limit = 10L
            val afterCursor = "item_cursor_xyz"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [],
                      "has_more": false
                    }
                """.trimIndent()))

            val params = ItemListParams.builder()
                .conversationId("conv_pagination")
                .limit(limit)
                .order(ItemListParams.Order.DESC)
                .after(afterCursor)
                .build()

            client.conversations().items().list(params)

            val trace = analyzeSpans().first()

            assertEquals(limit, trace.attributes[AttributeKey.longKey("tracy.request.limit")])
            assertEquals("desc", trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(afterCursor, trace.attributes[AttributeKey.stringKey("tracy.request.after")])
        }
    }

    @Test
    fun `list items response reads list metadata including has_more`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val firstId = "item_first_list"
            val lastId = "item_last_list"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "object": "list",
                      "data": [
                        {"id": "$firstId", "type": "message", "status": "completed"},
                        {"id": "$lastId", "type": "message", "status": "completed"}
                      ],
                      "first_id": "$firstId",
                      "last_id": "$lastId",
                      "has_more": true
                    }
                """.trimIndent()))

            val params = ItemListParams.builder()
                .conversationId("conv_meta")
                .build()

            client.conversations().items().list(params)

            val trace = analyzeSpans().first()

            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{conv_id}/items/{item_id} ============

    @Test
    fun `retrieve item sets operation name and traces item fields`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_test"
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
                      "conversation_id": "$conversationId"
                    }
                """.trimIndent()))

            val params = ItemRetrieveParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()

            client.conversations().items().retrieve(params)

            val trace = analyzeSpans().first()

            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    // ============ DELETE: DELETE /conversations/{conv_id}/items/{item_id} ============

    @Test
    fun `delete item sets operation name and traces id and created_at`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_delete_test"
            val itemId = "item_delete_001"
            val createdAt = 1700000000L

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "id": "$itemId",
                      "created_at": $createdAt
                    }
                """.trimIndent()))

            val params = ItemDeleteParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()

            client.conversations().items().delete(params)

            val trace = analyzeSpans().first()

            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
