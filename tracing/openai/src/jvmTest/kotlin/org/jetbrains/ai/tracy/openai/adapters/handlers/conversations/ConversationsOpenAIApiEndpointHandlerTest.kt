/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
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
import java.time.Instant

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * Uses [MockWebServer] and a plain [OkHttpClient] instrumented with [OpenAILLMTracingAdapter]
 * (no OpenAI SDK Conversations client is required).
 *
 * Covers three scenarios:
 * - **lifecycle**: CONVERSATION_CREATE, CONVERSATION_RETRIEVE, CONVERSATION_UPDATE, CONVERSATION_DELETE
 * - **items_lifecycle**: ITEMS_CREATE, ITEM_RETRIEVE, ITEM_DELETE
 * - **items_pagination**: ITEMS_LIST with cursor-based pagination parameters
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ HELPERS ============

    private fun MockWebServer.instrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun jsonBody(json: String) =
        json.trimIndent().toRequestBody("application/json".toMediaType())

    private fun MockWebServer.enqueueJson(code: Int = 200, body: String) {
        enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trimIndent())
        )
    }

    private fun MockWebServer.enqueueConversation(
        id: String = "conv_abc123",
        createdAt: Long = Instant.now().epochSecond,
    ) = enqueueJson(
        body = """
            {
              "id": "$id",
              "object": "conversation",
              "created_at": $createdAt
            }
        """
    )

    // ============ lifecycle: CONVERSATION_CREATE ============

    @Test
    fun `lifecycle - CONVERSATION_CREATE sets correct operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_create_01"
            val createdAt = 1710000001L

            server.enqueueConversation(id = convId, createdAt = createdAt)

            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .post(jsonBody("{}"))
                .build()
            client.newCall(request).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(
                "conversations.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(
                "conversations",
                trace.attributes[AttributeKey.stringKey("openai.api.type")]
            )
            assertEquals(
                convId,
                trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")]
            )
            assertEquals(
                createdAt,
                trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")]
            )
        }
    }

    @Test
    fun `lifecycle - CONVERSATION_CREATE with items in body traces item count`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            server.enqueueConversation()

            val body = """
                {
                  "items": [
                    {"type": "message", "role": "user", "content": "Hello"},
                    {"type": "message", "role": "assistant", "content": "Hi there"}
                  ]
                }
            """
            val request = Request.Builder()
                .url(server.url("/v1/conversations"))
                .post(jsonBody(body))
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.request.items.count")])
        }
    }

    // ============ lifecycle: CONVERSATION_RETRIEVE ============

    @Test
    fun `lifecycle - CONVERSATION_RETRIEVE sets correct operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_retrieve_01"
            val createdAt = 1710000002L

            server.enqueueConversation(id = convId, createdAt = createdAt)

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.retrieve",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ lifecycle: CONVERSATION_UPDATE ============

    @Test
    fun `lifecycle - CONVERSATION_UPDATE sets correct operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_update_01"
            val createdAt = 1710000003L

            server.enqueueConversation(id = convId, createdAt = createdAt)

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId"))
                .post(jsonBody("""{"metadata": {"tag": "updated"}}"""))
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.update",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ lifecycle: CONVERSATION_DELETE ============

    @Test
    fun `lifecycle - CONVERSATION_DELETE sets correct operation name, id and deleted flag`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_delete_01"

            server.enqueueJson(
                body = """
                    {
                      "id": "$convId",
                      "object": "conversation.deleted",
                      "deleted": true
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId"))
                .delete()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.delete",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    // ============ items_lifecycle: ITEMS_CREATE ============

    @Test
    fun `items_lifecycle - ITEMS_CREATE sets correct operation name and item count`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_items_01"

            server.enqueueJson(
                body = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "item_1", "type": "message", "status": "completed"},
                        {"id": "item_2", "type": "message", "status": "completed"}
                      ],
                      "first_id": "item_1",
                      "last_id": "item_2",
                      "has_more": false
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId/items"))
                .post(jsonBody("""{"items": [{"type": "message"}, {"type": "message"}]}"""))
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals("item_1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("item_2", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
            // request body items count
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.request.items.count")])
        }
    }

    // ============ items_lifecycle: ITEM_RETRIEVE ============

    @Test
    fun `items_lifecycle - ITEM_RETRIEVE sets correct operation name and item attributes`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_item_retrieve_01"
            val itemId = "item_retrieve_01"

            server.enqueueJson(
                body = """
                    {
                      "id": "$itemId",
                      "object": "conversation.item",
                      "type": "message",
                      "status": "completed"
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId/items/$itemId"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.retrieve",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    // ============ items_lifecycle: ITEM_DELETE ============

    @Test
    fun `items_lifecycle - ITEM_DELETE sets correct operation name, item id and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_item_delete_01"
            val itemId = "item_delete_01"

            server.enqueueJson(
                body = """
                    {
                      "id": "$itemId",
                      "object": "conversation.item.deleted",
                      "deleted": true,
                      "created_at": 1710000010
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId/items/$itemId"))
                .delete()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.delete",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            // item id extracted from URL path
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            // conversation id extracted from URL path
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ items_pagination: ITEMS_LIST ============

    @Test
    fun `items_pagination - ITEMS_LIST sets correct operation name and response attributes`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_list_01"

            server.enqueueJson(
                body = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "item_list_1", "type": "message", "status": "completed"},
                        {"id": "item_list_2", "type": "message", "status": "completed"},
                        {"id": "item_list_3", "type": "message", "status": "in_progress"}
                      ],
                      "first_id": "item_list_1",
                      "last_id": "item_list_3",
                      "has_more": false
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId/items"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.list",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals("item_list_1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("item_list_3", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    @Test
    fun `items_pagination - ITEMS_LIST with limit, order, after query params are traced`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_paginate_01"
            val limit = "20"
            val order = "desc"
            val after = "item_cursor_xyz"

            server.enqueueJson(
                body = """
                    {
                      "object": "list",
                      "data": [],
                      "first_id": null,
                      "last_id": null,
                      "has_more": false
                    }
                """
            )

            val url = server.url("/v1/conversations/$convId/items")
                .newBuilder()
                .addQueryParameter("limit", limit)
                .addQueryParameter("order", order)
                .addQueryParameter("after", after)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.list",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(limit, trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(after, trace.attributes[AttributeKey.stringKey("tracy.request.after")])
        }
    }

    @Test
    fun `items_pagination - ITEMS_LIST with has_more true indicates more pages available`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()
            val convId = "conv_paginate_02"

            server.enqueueJson(
                body = """
                    {
                      "object": "list",
                      "data": [
                        {"id": "item_page1_1", "type": "message", "status": "completed"},
                        {"id": "item_page1_2", "type": "message", "status": "completed"}
                      ],
                      "first_id": "item_page1_1",
                      "last_id": "item_page1_2",
                      "has_more": true
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/$convId/items"))
                .get()
                .build()
            client.newCall(request).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.list",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
            assertEquals("item_page1_1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("item_page1_2", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
        }
    }

    // ============ COMMON: error responses ============

    @Test
    fun `error response is traced with ERROR status for conversations endpoint`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            server.enqueueJson(
                code = 404,
                body = """
                    {
                      "error": {
                        "message": "No such conversation: conv_notfound",
                        "type": "invalid_request_error",
                        "param": null,
                        "code": "resource_not_found"
                      }
                    }
                """
            )

            val request = Request.Builder()
                .url(server.url("/v1/conversations/conv_notfound"))
                .get()
                .build()

            try {
                client.newCall(request).execute().close()
            } catch (_: Exception) {
                // may throw depending on OkHttp config
            }

            val trace = analyzeSpans().first()
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.error.message")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.error.code")])
        }
    }

    // ============ COMMON: openai.api.type is always set ============

    @Test
    fun `openai api type is always conversations regardless of route`() = runTest {
        withMockServer { server ->
            val client = server.instrumentedClient()

            // CONVERSATION_CREATE
            server.enqueueConversation()
            client.newCall(
                Request.Builder().url(server.url("/v1/conversations")).post(jsonBody("{}")).build()
            ).execute().close()

            // CONVERSATION_RETRIEVE
            server.enqueueConversation(id = "conv_x")
            client.newCall(
                Request.Builder().url(server.url("/v1/conversations/conv_x")).get().build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(2, traces)
            assertTrue(
                traces.all { it.attributes[AttributeKey.stringKey("openai.api.type")] == "conversations" },
                "All conversation traces should have openai.api.type = 'conversations'"
            )
        }
    }
}
