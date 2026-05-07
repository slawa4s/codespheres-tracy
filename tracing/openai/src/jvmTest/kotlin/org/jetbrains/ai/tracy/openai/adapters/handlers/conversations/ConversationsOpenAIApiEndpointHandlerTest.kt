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
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * These tests use [MockWebServer] with a mock API key and do not require access to the
 * real OpenAI Conversations API.
 *
 * Test scenarios correspond to:
 * - `openai/conversations/lifecycle` — create, retrieve, update, delete conversations
 * - `openai/conversations/items_lifecycle` — create, retrieve, delete conversation items
 * - `openai/conversations/items_pagination` — list items with pagination query parameters
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ UNIT TESTS: Route Detection ============

    @Test
    fun `detectRoute - POST without conversation ID returns CREATE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.CREATE, handler.detectRoute(url, "POST"))
    }

    @Test
    fun `detectRoute - GET with conversation ID returns RETRIEVE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.RETRIEVE, handler.detectRoute(url, "GET"))
    }

    @Test
    fun `detectRoute - POST with conversation ID returns UPDATE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.UPDATE, handler.detectRoute(url, "POST"))
    }

    @Test
    fun `detectRoute - DELETE with conversation ID returns DELETE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.DELETE, handler.detectRoute(url, "DELETE"))
    }

    @Test
    fun `detectRoute - POST with items segment returns ITEMS_CREATE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc", "items"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.ITEMS_CREATE, handler.detectRoute(url, "POST"))
    }

    @Test
    fun `detectRoute - GET with items segment and no item ID returns ITEMS_LIST`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc", "items"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.ITEMS_LIST, handler.detectRoute(url, "GET"))
    }

    @Test
    fun `detectRoute - GET with item ID returns ITEMS_RETRIEVE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc", "items", "item_xyz"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.ITEMS_RETRIEVE, handler.detectRoute(url, "GET"))
    }

    @Test
    fun `detectRoute - DELETE with item ID returns ITEMS_DELETE`() {
        val handler = ConversationsOpenAIApiEndpointHandler()
        val url = buildUrl(listOf("v1", "conversations", "conv_abc", "items", "item_xyz"))
        assertEquals(ConversationsOpenAIApiEndpointHandler.ConversationRoute.ITEMS_DELETE, handler.detectRoute(url, "DELETE"))
    }

    // ============ INTEGRATION: Conversation Lifecycle ============

    @Test
    fun `test CREATE conversation - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_create_123"
            val createdAt = Instant.now().epochSecond

            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

            client.conversations().create(ConversationCreateParams.none())

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `test RETRIEVE conversation - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_retrieve_456"
            val createdAt = Instant.now().epochSecond

            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

            val params = ConversationRetrieveParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().retrieve(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            // conversation ID should appear both from URL path (request) and response body
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `test UPDATE conversation - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_update_789"
            val createdAt = Instant.now().epochSecond

            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

            val params = ConversationUpdateParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().update(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.update", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.conversation.created_at")])
        }
    }

    @Test
    fun `test DELETE conversation - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_delete_abc"

            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$conversationId","deleted":true,"object":"conversation.deleted"}"""))

            val params = ConversationDeleteParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().delete(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.deleted")])
        }
    }

    @Test
    fun `test conversation lifecycle - create then retrieve then delete`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_lifecycle_001"
            val createdAt = Instant.now().epochSecond

            // CREATE
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)
            // RETRIEVE
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)
            // DELETE
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$conversationId","deleted":true,"object":"conversation.deleted"}"""))

            client.conversations().create(ConversationCreateParams.none())

            val retrieveParams = ConversationRetrieveParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().retrieve(retrieveParams)

            val deleteParams = ConversationDeleteParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().delete(deleteParams)

            val traces = analyzeSpans()
            assertTracesCount(3, traces)

            assertEquals("conversations.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.retrieve", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.delete", traces[2].attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            // All traces should have openai.api.type set
            for (trace in traces) {
                assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            }
        }
    }

    @Test
    fun `test conversation CREATE failure - error status is traced`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Bad request","type":"invalid_request_error","code":"bad_request"}}"""))

            try {
                client.conversations().create(ConversationCreateParams.none())
            } catch (_: Exception) {
                // Expected to fail
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            assertEquals(StatusCode.ERROR, traces.first().status.statusCode)
        }
    }

    // ============ INTEGRATION: Items Lifecycle ============

    @Test
    fun `test ITEMS_CREATE - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_create_001"
            val itemId = "item_create_001"

            server.enqueueItemListResponse(
                firstId = itemId,
                lastId = itemId,
                hasMore = false,
                items = listOf(Triple(itemId, "message", "completed"))
            )

            val params = ItemCreateParams.builder()
                .conversationId(conversationId)
                .build()
            client.conversations().items().create(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    @Test
    fun `test ITEMS_RETRIEVE - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_retrieve_001"
            val itemId = "item_retrieve_001"

            server.enqueueConversationItemResponse(id = itemId, type = "message", status = "completed")

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
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            // item ID set from URL path in request
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
            assertEquals("message", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.type")])
            assertEquals("completed", trace.attributes[AttributeKey.stringKey("tracy.conversation.item.status")])
        }
    }

    @Test
    fun `test ITEMS_DELETE - attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_delete_001"
            val itemId = "item_delete_001"
            val createdAt = Instant.now().epochSecond

            // Deleting an item returns the updated Conversation object
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

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
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            // item ID set from URL path in request
            assertEquals(itemId, trace.attributes[AttributeKey.stringKey("tracy.conversation.item.id")])
        }
    }

    @Test
    fun `test items lifecycle - create then retrieve then delete`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_items_lifecycle_001"
            val itemId = "item_lifecycle_001"
            val createdAt = Instant.now().epochSecond

            // CREATE conversation
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)
            // ITEMS_CREATE
            server.enqueueItemListResponse(
                firstId = itemId,
                lastId = itemId,
                hasMore = false,
                items = listOf(Triple(itemId, "message", "completed"))
            )
            // ITEMS_RETRIEVE
            server.enqueueConversationItemResponse(id = itemId, type = "message", status = "completed")
            // ITEMS_DELETE
            server.enqueueConversationResponse(id = conversationId, createdAt = createdAt)

            client.conversations().create(ConversationCreateParams.none())

            client.conversations().items().create(
                ItemCreateParams.builder().conversationId(conversationId).build()
            )

            client.conversations().items().retrieve(
                ItemRetrieveParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            client.conversations().items().delete(
                ItemDeleteParams.builder()
                    .conversationId(conversationId)
                    .itemId(itemId)
                    .build()
            )

            val traces = analyzeSpans()
            assertTracesCount(4, traces)

            assertEquals("conversations.create", traces[0].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.items.create", traces[1].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.items.retrieve", traces[2].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations.items.delete", traces[3].attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ INTEGRATION: Items Pagination ============

    @Test
    fun `test ITEMS_LIST - pagination attributes are traced correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_list_001"
            val firstId = "item_first"
            val lastId = "item_last"

            server.enqueueItemListResponse(
                firstId = firstId,
                lastId = lastId,
                hasMore = true,
                items = listOf(
                    Triple(firstId, "message", "completed"),
                    Triple(lastId, "message", "completed"),
                )
            )

            val limit = 2L
            val order = "asc"
            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .limit(limit)
                .order(ItemListParams.Order.ASC)
                .build()

            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(conversationId, trace.attributes[AttributeKey.stringKey("gen_ai.conversation.id")])
            assertEquals(limit.toString(), trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(order, trace.attributes[AttributeKey.stringKey("tracy.request.order")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.conversation.items.count")])
            assertEquals(firstId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.first_id")])
            assertEquals(lastId, trace.attributes[AttributeKey.stringKey("tracy.conversation.items.last_id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.conversation.items.has_more")])
        }
    }

    @Test
    fun `test ITEMS_LIST with after cursor - after attribute is traced`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_list_after_001"
            val afterCursor = "item_cursor_001"

            server.enqueueItemListResponse(
                firstId = "item_next",
                lastId = "item_next",
                hasMore = false,
                items = listOf(Triple("item_next", "message", "completed"))
            )

            val params = ItemListParams.builder()
                .conversationId(conversationId)
                .after(afterCursor)
                .build()

            client.conversations().items().list(params)

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("conversations.items.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(afterCursor, trace.attributes[AttributeKey.stringKey("tracy.request.after")])
        }
    }

    @Test
    fun `test ITEMS_LIST with empty result - count is zero`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val conversationId = "conv_list_empty_001"

            server.enqueueItemListResponse(
                firstId = null,
                lastId = null,
                hasMore = false,
                items = emptyList()
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

    // ============ HELPER METHODS ============

    /**
     * Enqueues a mock response with a Conversation JSON object.
     */
    private fun MockWebServer.enqueueConversationResponse(id: String, createdAt: Long) {
        enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"id":"$id","object":"conversation","created_at":$createdAt}"""))
    }

    /**
     * Enqueues a mock response with a ConversationItemList JSON object.
     *
     * @param items list of (id, type, status) triples
     */
    private fun MockWebServer.enqueueItemListResponse(
        firstId: String?,
        lastId: String?,
        hasMore: Boolean,
        items: List<Triple<String, String, String>>,
    ) {
        val dataJson = items.joinToString(",") { (id, type, status) ->
            """{"id":"$id","type":"$type","status":"$status","object":"conversation.item"}"""
        }
        val firstIdJson = if (firstId != null) """"first_id":"$firstId",""" else ""
        val lastIdJson = if (lastId != null) """"last_id":"$lastId",""" else ""
        enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"object":"list","data":[$dataJson],$firstIdJson${lastIdJson}"has_more":$hasMore}"""))
    }

    /**
     * Enqueues a mock response with a single ConversationItem JSON object.
     */
    private fun MockWebServer.enqueueConversationItemResponse(id: String, type: String, status: String) {
        enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"id":"$id","type":"$type","status":"$status","object":"conversation.item"}"""))
    }

    /**
     * Builds a [TracyHttpUrlImpl] with the given [segments] for unit-testing route detection.
     */
    private fun buildUrl(segments: List<String>) = TracyHttpUrlImpl(
        scheme = "https",
        host = "api.openai.com",
        pathSegments = segments,
        parameters = object : TracyQueryParameters {
            override fun queryParameter(name: String): String? = null
            override fun queryParameterValues(name: String): List<String?> = emptyList()
        }
    )

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
