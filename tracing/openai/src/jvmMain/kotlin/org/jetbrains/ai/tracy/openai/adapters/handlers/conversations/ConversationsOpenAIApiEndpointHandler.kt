/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedInput
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing persistent conversation objects
 * and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` - Update conversation metadata
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 5. `POST /conversations/{conversation_id}/items` - Add items to a conversation
 * 6. `GET /conversations/{conversation_id}/items` - List conversation items
 * 7. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a specific item
 * 8. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a specific item
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        when (route) {
            ConversationRoute.CREATE -> handleCreateRequest(span, request)
            ConversationRoute.RETRIEVE -> handleRetrieveRequest(span, request)
            ConversationRoute.UPDATE -> handleUpdateRequest(span, request)
            ConversationRoute.DELETE -> handleDeleteConversationRequest(span, request)
            ConversationRoute.CREATE_ITEM -> handleCreateItemRequest(span, request)
            ConversationRoute.LIST_ITEMS -> handleListItemsRequest(span, request)
            ConversationRoute.RETRIEVE_ITEM -> handleRetrieveItemRequest(span, request)
            ConversationRoute.DELETE_ITEM -> handleDeleteItemRequest(span, request)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        when (route) {
            ConversationRoute.CREATE -> handleConversationResponse(span, response)
            ConversationRoute.RETRIEVE -> handleConversationResponse(span, response)
            ConversationRoute.UPDATE -> handleConversationResponse(span, response)
            ConversationRoute.DELETE -> handleDeleteConversationResponse(span, response)
            ConversationRoute.CREATE_ITEM -> handleItemResponse(span, response)
            ConversationRoute.LIST_ITEMS -> handleListItemsResponse(span, response)
            ConversationRoute.RETRIEVE_ITEM -> handleItemResponse(span, response)
            ConversationRoute.DELETE_ITEM -> handleDeleteItemResponse(span, response)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent event streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    // ============ REQUEST HANDLERS ============

    private fun handleCreateRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["metadata"]?.let {
            span.setAttribute("gen_ai.request.metadata", it.toString().orRedactedInput())
        }
        val items = body["items"]
        if (items is JsonArray) {
            span.setAttribute("gen_ai.request.items_count", items.size.toLong())
        }
    }

    private fun handleRetrieveRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
    }

    private fun handleUpdateRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        val body = request.body.asJson()?.jsonObject ?: return
        body["metadata"]?.let {
            span.setAttribute("gen_ai.request.metadata", it.toString().orRedactedInput())
        }
    }

    private fun handleDeleteConversationRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
    }

    private fun handleCreateItemRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        val body = request.body.asJson()?.jsonObject ?: return
        body["role"]?.let {
            span.setAttribute("gen_ai.request.item.role", it.jsonPrimitive.content.orRedactedInput())
        }
        body["type"]?.let {
            span.setAttribute("gen_ai.request.item.type", it.jsonPrimitive.content)
        }
    }

    private fun handleListItemsRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("before")?.let { span.setAttribute("gen_ai.request.before", it) }
        params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
    }

    private fun handleRetrieveItemRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        val itemId = extractItemIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        if (itemId != null) {
            span.setAttribute("gen_ai.request.item.requested_id", itemId)
        } else {
            logger.warn { "Failed to extract item ID from URL: ${request.url}" }
        }
    }

    private fun handleDeleteItemRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        val itemId = extractItemIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        if (itemId != null) {
            span.setAttribute("gen_ai.request.item.requested_id", itemId)
        } else {
            logger.warn { "Failed to extract item ID from URL: ${request.url}" }
        }
    }

    // ============ RESPONSE HANDLERS ============

    private fun handleConversationResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        traceConversation(span, body, "gen_ai.response.conversation")
    }

    private fun handleDeleteConversationResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let {
            span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content)
        }
        body["deleted"]?.let {
            span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean)
        }
    }

    private fun handleItemResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        traceConversationItem(span, body, "gen_ai.response.item")
    }

    private fun handleListItemsResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
        body["has_more"]?.let { span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean) }
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.items_count", data.size.toLong())
            for ((index, itemElement) in data.withIndex()) {
                if (itemElement is JsonObject) {
                    traceConversationItem(span, itemElement, "gen_ai.response.items.$index")
                }
            }
        } else {
            span.setAttribute("gen_ai.response.items_count", 0L)
        }
    }

    private fun handleDeleteItemResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let {
            span.setAttribute("gen_ai.response.item.id", it.jsonPrimitive.content)
        }
        body["deleted"]?.let {
            span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean)
        }
    }

    // ============ ROUTE DETECTION ============

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }
        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1
        val hasItemId = hasItems && segments.size > (itemsIndex + 1) && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !hasItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.CREATE_ITEM
            method == "GET" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.LIST_ITEMS
            method == "GET" && hasConversationId && hasItems && hasItemId -> ConversationRoute.RETRIEVE_ITEM
            method == "DELETE" && hasConversationId && hasItems && hasItemId -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute {
        CREATE,           // POST /conversations
        RETRIEVE,         // GET /conversations/{id}
        UPDATE,           // POST /conversations/{id}
        DELETE,           // DELETE /conversations/{id}
        CREATE_ITEM,      // POST /conversations/{id}/items
        LIST_ITEMS,       // GET /conversations/{id}/items
        RETRIEVE_ITEM,    // GET /conversations/{id}/items/{item_id}
        DELETE_ITEM,      // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * Extracts the conversation ID from a path like `/v1/conversations/{id}` or
 * `/v1/conversations/{id}/items`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")
    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val potentialId = segments[conversationsIndex + 1]
        if (potentialId.isNotBlank() && potentialId != "items") potentialId else null
    } else {
        null
    }
}

/**
 * Extracts the item ID from a path like `/v1/conversations/{id}/items/{item_id}`.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    return if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
        val potentialId = segments[itemsIndex + 1]
        if (potentialId.isNotBlank()) potentialId else null
    } else {
        null
    }
}

/**
 * Traces a Conversation object.
 *
 * Conversation schema:
 * - id: string
 * - created_at: number
 * - metadata: object
 * - object: "conversation"
 */
internal fun traceConversation(span: Span, conversation: JsonObject, prefix: String) {
    conversation["id"]?.let {
        span.setAttribute("$prefix.id", it.jsonPrimitive.content)
    }
    conversation["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.created_at", it)
    }
    conversation["metadata"]?.let {
        if (it !is JsonNull) {
            span.setAttribute("$prefix.metadata", it.toString().orRedactedOutput())
        }
    }
    conversation["object"]?.let {
        span.setAttribute("$prefix.object", it.jsonPrimitive.content)
    }
}

/**
 * Traces a ConversationItem object.
 *
 * ConversationItem schema:
 * - id: string
 * - object: "conversation.item"
 * - type: string
 */
internal fun traceConversationItem(span: Span, item: JsonObject, prefix: String) {
    item["id"]?.let {
        span.setAttribute("$prefix.id", it.jsonPrimitive.content)
    }
    item["object"]?.let {
        span.setAttribute("$prefix.object", it.jsonPrimitive.content)
    }
    item["type"]?.let {
        span.setAttribute("$prefix.type", it.jsonPrimitive.content)
    }
}
