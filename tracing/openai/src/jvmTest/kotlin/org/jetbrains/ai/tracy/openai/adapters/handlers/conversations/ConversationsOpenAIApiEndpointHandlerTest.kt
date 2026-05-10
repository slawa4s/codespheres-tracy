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
import org.jetbrains.ai.tracy.core.OpenTelemetryOkHttpInterceptor
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for the Conversations API item handlers.
 *
 * Uses [MockWebServer] and a raw [OkHttpClient] with [OpenTelemetryOkHttpInterceptor]
 * — no real OpenAI API key or network access is required.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun instrumentedClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(OpenTelemetryOkHttpInterceptor(adapter = OpenAILLMTracingAdapter()))
            .build()

    private val jsonBody = "{}".toRequestBody("application/json".toMediaType())

    // ============ ITEMS CREATE: POST /conversations/{id}/items ============

    @Test
    fun `items create sets operation name and conversation id`() = runTest {
        withMockServer { server ->
            val client = instrumentedClient()
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
                    .post(jsonBody)
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
            val client = instrumentedClient()

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
                    .post(jsonBody)
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
            val client = instrumentedClient()
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
            val client = instrumentedClient()

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
            val client = instrumentedClient()

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
            val client = instrumentedClient()
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
            val client = instrumentedClient()

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
            val client = instrumentedClient()
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
            val client = instrumentedClient()
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
            // Body id is the parent conversation id
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            // created_at of the conversation
            assertEquals(1710000200L, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
            // Item id from URL
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
        }
    }
}
