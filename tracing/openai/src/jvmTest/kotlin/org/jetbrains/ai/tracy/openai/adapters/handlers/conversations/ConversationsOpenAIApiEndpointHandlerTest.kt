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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] and a mock API key, so they do not require access
 * to the real OpenAI Conversations API.
 *
 * The primary assertion in each test is that `gen_ai.operation.name` is set to the
 * correct route-derived value, overriding the wrong value that
 * `OpenAIApiUtils.setCommonResponseAttributes()` would derive from the response body's
 * `object` field (e.g. `"conversation"`, `"list"`, `"conversation.deleted"`).
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ POST /conversations — CREATE ============

    @Test
    fun `test CREATE conversation sets operation name to conversations-create`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueueConversationResponse("conv_123")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post("""{"model":"gpt-4o"}""".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ GET /conversations/{id} — RETRIEVE ============

    @Test
    fun `test RETRIEVE conversation sets operation name to conversations-retrieve`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueueConversationResponse("conv_123")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123"))
                    .get()
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.retrieve",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ POST /conversations/{id} — UPDATE ============

    @Test
    fun `test UPDATE conversation sets operation name to conversations-update`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueueConversationResponse("conv_123")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123"))
                    .post("""{"metadata":{"key":"value"}}""".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.update",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ DELETE /conversations/{id} — DELETE ============

    @Test
    fun `test DELETE conversation sets operation name to conversations-delete`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"conv_123","object":"conversation.deleted","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123"))
                    .delete()
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.delete",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ POST /conversations/{id}/items — ITEMS_CREATE ============

    @Test
    fun `test ITEMS CREATE sets operation name to conversations-items-create`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"item_456","object":"conversation.item","conversation_id":"conv_123"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123/items"))
                    .post("""{"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}""".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ GET /conversations/{id}/items — ITEMS_LIST ============

    @Test
    fun `test ITEMS LIST sets operation name to conversations-items-list`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[],"has_more":false}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123/items"))
                    .get()
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.list",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ GET /conversations/{id}/items/{item_id} — ITEMS_RETRIEVE ============

    @Test
    fun `test ITEMS RETRIEVE sets operation name to conversations-items-retrieve`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"item_456","object":"conversation.item","conversation_id":"conv_123"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123/items/item_456"))
                    .get()
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.retrieve",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ DELETE /conversations/{id}/items/{item_id} — ITEMS_DELETE ============

    @Test
    fun `test ITEMS DELETE sets operation name to conversations-items-delete`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"item_456","object":"conversation.item.deleted","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/conv_123/items/item_456"))
                    .delete()
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            assertEquals(
                "conversations.items.delete",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ Verify override — response body `object` field is not used ============

    @Test
    fun `test operation name is overridden regardless of response object field`() = runTest {
        withMockServer { server ->
            val client = createInstrumentedOkHttpClient()

            // The response has object="conversation" which setCommonResponseAttributes() would
            // incorrectly set as gen_ai.operation.name; the handler must override it.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"conv_123","object":"conversation","model":"gpt-4o"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations"))
                    .post("""{"model":"gpt-4o"}""".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { }

            val trace = analyzeSpans().first()
            // Must be route-derived "conversations.create", not the body's "conversation"
            assertEquals(
                "conversations.create",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ Helper methods ============

    private fun createInstrumentedOkHttpClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun MockWebServer.enqueueConversationResponse(id: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","object":"conversation","model":"gpt-4o"}""")
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
