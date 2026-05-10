/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.AttributeKey
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON = "application/json".toMediaType()
private val EMPTY_BODY = "{}".toRequestBody(JSON)

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder()
            .build()

    private fun MockWebServer.enqueueConversationResponse(
        id: String = "conv_abc123",
        createdAt: Long = 1_700_000_000L,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","object":"conversation","created_at":$createdAt}""")
        )
    }

    private fun MockWebServer.enqueueDeleteResponse(id: String = "conv_abc123") {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","deleted":true,"object":"conversation.deleted"}""")
        )
    }

    // ============ CREATE: POST /conversations ============

    @Test
    fun `CREATE sets conversations_create operation name and conversation id`() = runTest {
        withMockServer { server ->
            val convId = "conv_create_001"
            server.enqueueConversationResponse(id = convId)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post(EMPTY_BODY)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `CREATE sets tracy_conversation_created_at from response`() = runTest {
        withMockServer { server ->
            val createdAt = 1_234_567_890L
            server.enqueueConversationResponse(createdAt = createdAt)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post(EMPTY_BODY)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `RETRIEVE sets conversations_retrieve operation name and conversation id`() = runTest {
        withMockServer { server ->
            val convId = "conv_retrieve_001"
            server.enqueueConversationResponse(id = convId)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId"))
                    .get()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ UPDATE: PATCH /conversations/{id} ============

    @Test
    fun `UPDATE sets conversations_update operation name and conversation id`() = runTest {
        withMockServer { server ->
            val convId = "conv_update_001"
            server.enqueueConversationResponse(id = convId)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId"))
                    .patch(EMPTY_BODY)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `DELETE sets conversations_delete operation name and conversation id`() = runTest {
        withMockServer { server ->
            val convId = "conv_delete_001"
            server.enqueueDeleteResponse(id = convId)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId"))
                    .delete()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `DELETE sets tracy_conversation_deleted true from response`() = runTest {
        withMockServer { server ->
            server.enqueueDeleteResponse()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_abc123"))
                    .delete()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    // ============ ITEMS CREATE: POST /conversations/{id}/items ============

    @Test
    fun `items create sets operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()
            val convId = "conv-abc123"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"object":"list","data":[{"id":"item-1","type":"message","status":"completed"}],"first_id":"item-1","last_id":"item-1","has_more":false}"""
                    )
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId/items"))
                    .post(EMPTY_BODY)
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `items create response sets list pagination attributes`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"object":"list","data":[{"id":"item-1"},{"id":"item-2"}],"first_id":"item-1","last_id":"item-2","has_more":false}"""
                    )
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv-xyz/items"))
                    .post(EMPTY_BODY)
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals("item-1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("item-2", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
        }
    }

    // ============ ITEMS LIST: GET /conversations/{id}/items ============

    @Test
    fun `items list sets operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()
            val convId = "conv-list-456"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[],"first_id":null,"last_id":null,"has_more":false}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId/items"))
                    .get()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `items list captures query parameters`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[],"has_more":false}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv-q/items?limit=5&order=desc&after=item-prev"))
                    .get()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("5", trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals("desc", trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals("item-prev", trace.attributes[AttributeKey.stringKey("tracy.request.after")])
        }
    }

    @Test
    fun `items list response sets pagination and has_more`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"object":"list","data":[{"id":"item-a"},{"id":"item-b"},{"id":"item-c"}],"first_id":"item-a","last_id":"item-c","has_more":true}"""
                    )
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv-p/items"))
                    .get()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(3L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals("item-a", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("item-c", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ ITEMS RETRIEVE: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `items retrieve sets operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()
            val convId = "conv-ret-789"
            val itemId = "item-ret-001"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$itemId","type":"message","status":"completed"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId/items/$itemId"))
                    .get()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `items retrieve response sets item id, type, and status`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"item-xyz","type":"function_call","status":"in_progress"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv-r/items/item-xyz"))
                    .get()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("item-xyz", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("function_call", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("in_progress", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    // ============ ITEMS DELETE: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `items delete sets operation name and conversation id from request URL`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()
            val convId = "conv-del-111"
            val itemId = "item-del-999"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$convId","created_at":1710000100}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId/items/$itemId"))
                    .delete()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `items delete response sets conversation id from body and item id from URL`() = runTest {
        withMockServer { server ->
            val client = server.makeInstrumentedClient()
            val convId = "conv-del-222"
            val itemId = "item-del-888"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$convId","created_at":1710000200}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId/items/$itemId"))
                    .delete()
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(1710000200L, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
