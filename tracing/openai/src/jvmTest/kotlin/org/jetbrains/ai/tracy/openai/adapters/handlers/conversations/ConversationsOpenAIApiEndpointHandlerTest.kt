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

    // ============ HELPERS ============

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
                .setBody(
                    """{"id":"$id","object":"conversation","created_at":$createdAt}"""
                )
        )
    }

    private fun MockWebServer.enqueueDeleteResponse(
        id: String = "conv_abc123",
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","deleted":true,"object":"conversation.deleted"}"""
                )
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

    @Test
    fun `RETRIEVE sets conversation id from URL on request span`() = runTest {
        withMockServer { server ->
            val convId = "conv_url_id_001"
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
            assertEquals(convId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `UPDATE sets conversations_update operation name and conversation id`() = runTest {
        withMockServer { server ->
            val convId = "conv_update_001"
            server.enqueueConversationResponse(id = convId)

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/conversations/$convId"))
                    .post(EMPTY_BODY)
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

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
