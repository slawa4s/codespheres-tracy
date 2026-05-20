/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.ConversationDeleteParams
import com.openai.models.conversations.ConversationRetrieveParams
import com.openai.models.conversations.ConversationUpdateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.conversations.items.ItemDeleteParams
import com.openai.models.conversations.items.ItemListParams
import com.openai.models.conversations.items.ItemRetrieveParams
import com.openai.models.responses.EasyInputMessage
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.core.TracingManager
import org.jetbrains.ai.tracy.core.policy.ContentCapturePolicy
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they do not
 * require access to the real OpenAI Conversations API or any specific account configuration.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /conversations ============

    @Test
    fun `test CREATE conversation endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_create_123"

            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ConversationCreateParams.builder().build()
            client.conversations().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.created_at")])
        }
    }

    @Test
    fun `test CREATE conversation traces metadata and items with redaction`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = false, captureOutputs = false)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueConversationResponse(id = "conv_with_items"))

            val params = ConversationCreateParams.builder()
                .metadata(ConversationCreateParams.Metadata.builder().build())
                .addItem(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("secret prompt")
                        .build()
                )
                .build()
            client.conversations().create(params)

            val trace = analyzeSpans().first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.request.metadata")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.request.items.count")])
            // role is well-known → verbatim
            assertEquals("user", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.role")])
            // content is sensitive → redacted under default policy
            assertEquals("REDACTED", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.content")])
        }
    }

    @Test
    fun `test CREATE conversation traces item content verbatim when input capture is enabled`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            TracingManager.withCapturingPolicy(
                ContentCapturePolicy(captureInputs = true, captureOutputs = true)
            )

            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueConversationResponse(id = "conv_with_items_verbatim"))

            val params = ConversationCreateParams.builder()
                .addItem(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("Hello world")
                        .build()
                )
                .build()
            client.conversations().create(params)

            val trace = analyzeSpans().first()

            assertEquals("user", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.role")])
            // content is a JsonPrimitive string here, so its .content is the raw string (unquoted)
            assertEquals("Hello world", trace.attributes[AttributeKey.stringKey("tracy.request.items.0.content")])
        }
    }

    // ============ RETRIEVE: GET /conversations/{id} ============

    @Test
    fun `test RETRIEVE conversation endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_456"

            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ConversationRetrieveParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().retrieve(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.created_at")])
        }
    }

    // ============ UPDATE: POST /conversations/{id} ============

    @Test
    fun `test UPDATE conversation endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_update_789"

            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ConversationUpdateParams.builder()
                .conversationId(conversationId)
                .metadata(ConversationUpdateParams.Metadata.builder().build())
                .build()
            client.conversations().update(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.created_at")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.request.metadata")])
        }
    }

    // ============ DELETE: DELETE /conversations/{id} ============

    @Test
    fun `test DELETE conversation endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
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
                          "deleted": true,
                          "object": "conversation.deleted"
                        }
                        """.trimIndent()
                    )
            )

            val params = ConversationDeleteParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().delete(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.deleted")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.response.id")])
            assertEquals("conversation.deleted", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
        }
    }

    // ============ ITEMS_CREATE: POST /conversations/{id}/items ============

    @Test
    fun `test ITEMS_CREATE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_create_001"

            server.enqueue(enqueueConversationItemListResponse(count = 1))

            val params = ItemCreateParams.builder()
                .conversationId(conversationId)
                .addItem(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("Hello")
                        .build()
                )
                .build()
            client.conversations().items().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertNotNull(trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ ITEMS_LIST: GET /conversations/{id}/items ============

    @Test
    fun `test ITEMS_LIST endpoint gets traced with pagination attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_002"
            val limit = 5L
            val order = "desc"
            val after = "msg_abc123"

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
                              "id": "msg_item_1",
                              "type": "message",
                              "status": "completed",
                              "role": "user",
                              "content": []
                            }
                          ],
                          "first_id": "msg_item_1",
                          "last_id": "msg_item_1",
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .limit(limit)
                .order(ItemListParams.Order.DESC)
                .after(after)
                .build()
            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])

            // Verify pagination request params are traced
            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(after, trace.attributes[AttributeKey.stringKey("tracy.request.after")])

            // Verify response list metadata
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals("msg_item_1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals("msg_item_1", trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    @Test
    fun `test ITEMS_LIST endpoint with empty list gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_list_empty"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "object": "list",
                          "data": [],
                          "first_id": null,
                          "last_id": null,
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(0L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    // ============ ITEMS_RETRIEVE: GET /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_RETRIEVE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_item_retrieve_003"
            val itemId = "msg_retrieve_xyz"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$itemId",
                          "type": "message",
                          "status": "completed",
                          "role": "assistant",
                          "content": []
                        }
                        """.trimIndent()
                    )
            )

            val params = ItemRetrieveParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()
            client.conversations().items().retrieve(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    // ============ ITEMS_DELETE: DELETE /conversations/{id}/items/{item_id} ============

    @Test
    fun `test ITEMS_DELETE endpoint gets traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_item_delete_004"
            val itemId = "msg_delete_abc"

            // ITEMS_DELETE returns the parent Conversation object
            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ItemDeleteParams.builder()
                .conversationId(conversationId)
                .itemId(itemId)
                .build()
            client.conversations().items().delete(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    // ============ CONVERSATIONS_ID extracted from path (non-CREATE routes) ============

    @Test
    fun `test conversation ID is extracted from URL path for non-CREATE routes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_path_test_999"

            server.enqueue(enqueueConversationResponse(id = conversationId))

            val params = ConversationRetrieveParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().retrieve(params)

            val trace = analyzeSpans().first()

            // conversation_id should be set from URL path in the request handler under tracy.request.*,
            // and from the response body in the response handler under gen_ai.conversation.id + tracy.response.id.
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.request.conversation_id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("tracy.response.id")])
        }
    }

    // ============ Lifecycle tests ============

    @Test
    fun `test conversations lifecycle - create then retrieve`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_lifecycle_001"

            server.enqueue(enqueueConversationResponse(id = conversationId))
            server.enqueue(enqueueConversationResponse(id = conversationId))

            // Create
            client.conversations().create(ConversationCreateParams.builder().build())
            // Retrieve
            client.conversations().retrieve(
                ConversationRetrieveParams.builder().conversationId(conversationId).build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)

            val createTrace = traces[0]
            val retrieveTrace = traces[1]

            assertEquals("conversations.create", createTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.retrieve", retrieveTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            assertEquals(conversationId, createTrace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(conversationId, retrieveTrace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
        }
    }

    @Test
    fun `test conversations items lifecycle - create items then list`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_lifecycle_002"

            server.enqueue(enqueueConversationItemListResponse(count = 1))
            server.enqueue(enqueueConversationItemListResponse(count = 1))

            // Create items
            client.conversations().items().create(
                ItemCreateParams.builder()
                    .conversationId(conversationId)
                    .addItem(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content("Hello")
                            .build()
                    )
                    .build()
            )
            // List items
            client.conversations().items().list(
                ItemListParams.builder().conversationId(conversationId).build()
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)

            assertEquals("conversations.items.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.items.list", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueConversationResponse(
        id: String,
        createdAt: Long = Instant.now().epochSecond
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "conversation",
                  "created_at": $createdAt,
                  "metadata": {}
                }
                """.trimIndent()
            )
    }

    private fun enqueueConversationItemListResponse(
        count: Int,
        firstId: String = "msg_first_001",
        lastId: String = "msg_last_001",
        hasMore: Boolean = false
    ): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """
            {
              "id": "msg_item_$i",
              "type": "message",
              "status": "completed",
              "role": "user",
              "content": []
            }
            """.trimIndent()
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "object": "list",
                  "data": [$items],
                  "first_id": "$firstId",
                  "last_id": "$lastId",
                  "has_more": $hasMore
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
