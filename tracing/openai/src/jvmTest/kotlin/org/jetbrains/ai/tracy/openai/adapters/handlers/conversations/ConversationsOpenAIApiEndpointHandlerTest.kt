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
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [MockWebServer] and a raw instrumented [OkHttpClient], so they do not require
 * access to the real OpenAI Conversations API or any specific account configuration.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation - sets operation name and api type`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID)

            post(client, server.url("/v1/conversations").toString(), "{}")

            val trace = analyzeSpans().first()
            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE conversation - extracts conversation id and created_at from response`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID, createdAt = CREATED_AT)

            post(client, server.url("/v1/conversations").toString(), "{}")

            val trace = analyzeSpans().first()
            assertEquals(CONVERSATION_ID, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(CREATED_AT, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation - sets operation name and api type`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID)

            get(client, server.url("/v1/conversations/$CONVERSATION_ID").toString())

            val trace = analyzeSpans().first()
            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test RETRIEVE conversation - extracts conversation id from path and response`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID, createdAt = CREATED_AT)

            get(client, server.url("/v1/conversations/$CONVERSATION_ID").toString())

            val trace = analyzeSpans().first()
            assertEquals(CONVERSATION_ID, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(CREATED_AT, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation - sets operation name and api type`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID)

            post(client, server.url("/v1/conversations/$CONVERSATION_ID").toString(), "{}")

            val trace = analyzeSpans().first()
            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test UPDATE conversation - extracts conversation id from path and response`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueConversationResponse(CONVERSATION_ID, createdAt = CREATED_AT)

            post(client, server.url("/v1/conversations/$CONVERSATION_ID").toString(), "{}")

            val trace = analyzeSpans().first()
            assertEquals(CONVERSATION_ID, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(CREATED_AT, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation - sets operation name and api type`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueDeleteResponse(CONVERSATION_ID)

            delete(client, server.url("/v1/conversations/$CONVERSATION_ID").toString())

            val trace = analyzeSpans().first()
            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test DELETE conversation - extracts conversation id and deleted flag from response`() = runTest(timeout = 1.minutes) {
        withMockServer { server ->
            val client = instrumentedClient()
            server.enqueueDeleteResponse(CONVERSATION_ID)

            delete(client, server.url("/v1/conversations/$CONVERSATION_ID").toString())

            val trace = analyzeSpans().first()
            assertEquals(CONVERSATION_ID, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    // ============ HELPER METHODS ============

    private fun instrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun post(client: OkHttpClient, url: String, body: String) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $MOCK_API_KEY")
            .build()
        client.newCall(request).execute().use { }
    }

    private fun get(client: OkHttpClient, url: String) {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $MOCK_API_KEY")
            .build()
        client.newCall(request).execute().use { }
    }

    private fun delete(client: OkHttpClient, url: String) {
        val request = Request.Builder()
            .url(url)
            .delete()
            .addHeader("Authorization", "Bearer $MOCK_API_KEY")
            .build()
        client.newCall(request).execute().use { }
    }

    private fun MockWebServer.enqueueConversationResponse(
        id: String,
        createdAt: Long = CREATED_AT,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","object":"realtime.conversation","created_at":$createdAt}"""
                )
        )
    }

    private fun MockWebServer.enqueueDeleteResponse(id: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$id","object":"realtime.conversation.deleted","deleted":true}"""
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
        private const val CONVERSATION_ID = "conv_abc123"
        private const val CREATED_AT = 1700000000L
    }
}
