/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing persistent conversation objects
 * and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` - Update conversation metadata
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 5. `POST /conversations/{conversation_id}/items` - Create items in a conversation
 * 6. `GET /conversations/{conversation_id}/items` - List items in a conversation
 * 7. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a specific item
 * 8. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a specific item
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        when (route) {
            ConversationRoute.CREATE -> handleCreateConversationRequest(span, request)
            ConversationRoute.RETRIEVE -> handleConversationIdRequest(span, request)
            ConversationRoute.UPDATE -> handleConversationIdRequest(span, request)
            ConversationRoute.DELETE -> handleConversationIdRequest(span, request)
            ConversationRoute.CREATE_ITEMS -> handleCreateItemsRequest(span, request)
            ConversationRoute.LIST_ITEMS -> handleListItemsRequest(span, request)
            ConversationRoute.RETRIEVE_ITEM -> handleItemRequest(span, request)
            ConversationRoute.DELETE_ITEM -> handleItemRequest(span, request)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        when (route) {
            ConversationRoute.CREATE -> traceConversationObject(span, response)
            ConversationRoute.RETRIEVE -> traceConversationObject(span, response)
            ConversationRoute.UPDATE -> traceConversationObject(span, response)
            ConversationRoute.DELETE -> traceDeleteResponse(span, response)
            ConversationRoute.CREATE_ITEMS -> traceItemListResponse(span, response)
            ConversationRoute.LIST_ITEMS -> traceItemListResponse(span, response)
            ConversationRoute.RETRIEVE_ITEM -> traceItemResponse(span, response)
            ConversationRoute.DELETE_ITEM -> traceConversationObject(span, response)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    // ---- Route detection ----

    /**
     * Detects which specific conversation endpoint is being called based on the URL path
     * and HTTP method.
     *
     * URL patterns:
     * - `POST /conversations` → CREATE
     * - `GET /conversations/{id}` → RETRIEVE
     * - `POST /conversations/{id}` → UPDATE
     * - `DELETE /conversations/{id}` → DELETE
     * - `POST /conversations/{id}/items` → CREATE_ITEMS
     * - `GET /conversations/{id}/items` → LIST_ITEMS
     * - `GET /conversations/{id}/items/{item_id}` → RETRIEVE_ITEM
     * - `DELETE /conversations/{id}/items/{item_id}` → DELETE_ITEM
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversation route. No `conversations` segment in path: " +
                        segments.joinToString("/")
            }
            return ConversationRoute.RETRIEVE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItemsSegment = itemsIndex != -1
        val hasItemId = hasItemsSegment && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !hasItemsSegment -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE
            method == "POST" && hasItemsSegment -> ConversationRoute.CREATE_ITEMS
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.LIST_ITEMS
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.RETRIEVE_ITEM
            method == "DELETE" && hasItemsSegment -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn {
                    "Failed to detect conversation route: $method ${segments.joinToString("/")}"
                }
                ConversationRoute.RETRIEVE
            }
        }
    }

    // ---- Request handlers ----

    /**
     * `POST /conversations` — optional `items` list in body.
     */
    private fun handleCreateConversationRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        val items = body["items"]
        if (items is JsonArray) {
            span.setAttribute("gen_ai.request.items_count", items.size.toLong())
        }
    }

    /**
     * Routes that only carry a conversation ID in the path (RETRIEVE, UPDATE, DELETE).
     */
    private fun handleConversationIdRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.id", it)
        }
    }

    /**
     * `POST /conversations/{id}/items` — conversation ID + item count in body.
     */
    private fun handleCreateItemsRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.id", it)
        }
        val body = request.body.asJson()?.jsonObject ?: return
        val items = body["items"]
        if (items is JsonArray) {
            span.setAttribute("gen_ai.request.items_count", items.size.toLong())
        }
    }

    /**
     * `GET /conversations/{id}/items` — conversation ID + pagination query params.
     */
    private fun handleListItemsRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.id", it)
        }
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
    }

    /**
     * Routes that carry both a conversation ID and an item ID (RETRIEVE_ITEM, DELETE_ITEM).
     */
    private fun handleItemRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.id", it)
        }
        extractItemId(request.url)?.let {
            span.setAttribute("gen_ai.request.item.id", it)
        }
    }

    // ---- Response handlers ----

    /**
     * Traces a Conversation object: `id`, `created_at`, `object`.
     *
     * Conversation schema:
     * - id: string
     * - created_at: number
     * - object: "conversation"
     * - metadata: object (key-value pairs)
     */
    private fun traceConversationObject(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let {
            span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.created_at", it)
        }
        body["object"]?.let {
            span.setAttribute("gen_ai.operation.name", it.jsonPrimitive.content)
        }
    }

    /**
     * Traces a deleted-conversation response: `id`, `deleted`, `object`.
     */
    private fun traceDeleteResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let {
            span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content)
        }
        body["deleted"]?.let {
            span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean)
        }
    }

    /**
     * Traces a ConversationItemList response: `data` count, `first_id`, `last_id`, `has_more`.
     */
    private fun traceItemListResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["first_id"]?.let {
            span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content)
        }
        body["last_id"]?.let {
            span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content)
        }
        body["has_more"]?.let {
            span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean)
        }
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.items_count", data.size.toLong())
        } else {
            span.setAttribute("gen_ai.response.items_count", 0L)
        }
    }

    /**
     * Traces a single ConversationItem response: `id`, `type`, `status`.
     */
    private fun traceItemResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let {
            span.setAttribute("gen_ai.response.item.id", it.jsonPrimitive.content)
        }
        body["type"]?.let {
            span.setAttribute("gen_ai.response.item.type", it.jsonPrimitive.content)
        }
        body["status"]?.let {
            span.setAttribute("gen_ai.response.item.status", it.jsonPrimitive.content)
        }
    }

    // ---- Path helpers ----

    /**
     * Extracts `conversation_id` from a path like
     * `/v1/conversations/{conversation_id}` or `/v1/conversations/{conversation_id}/items`.
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val candidate = segments[conversationsIndex + 1]
        return if (candidate.isNotBlank() && candidate != "items") candidate else null
    }

    /**
     * Extracts `item_id` from a path like
     * `/v1/conversations/{conversation_id}/items/{item_id}`.
     */
    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
        val candidate = segments[itemsIndex + 1]
        return if (candidate.isNotBlank()) candidate else null
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute {
        CREATE,         // POST /conversations
        RETRIEVE,       // GET /conversations/{id}
        UPDATE,         // POST /conversations/{id}
        DELETE,         // DELETE /conversations/{id}
        CREATE_ITEMS,   // POST /conversations/{id}/items
        LIST_ITEMS,     // GET /conversations/{id}/items
        RETRIEVE_ITEM,  // GET /conversations/{id}/items/{item_id}
        DELETE_ITEM,    // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
