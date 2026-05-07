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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations Items API.
 *
 * The Conversations Items API provides endpoints for managing conversation items:
 * 1. `POST /conversations/{conversation_id}/items` - Create a conversation item
 * 2. `GET /conversations/{conversation_id}/items` - List conversation items (paginated)
 * 3. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a conversation item
 * 4. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
 *
 * See [OpenAI Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // No specific request attributes extracted for conversations items endpoints
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        when (route) {
            ItemsRoute.CREATE, ItemsRoute.LIST -> handleItemsListResponse(span, response)
            ItemsRoute.RETRIEVE -> handleItemRetrieveResponse(span, response)
            ItemsRoute.DELETE -> handleItemDeleteResponse(span, response)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations items API does not support SSE streaming
        logger.warn { "Conversations items API does not use server-sent events streaming" }
    }

    /**
     * Handles list-style responses for both CREATE and LIST routes.
     *
     * Response schema: { data: ConversationItem[], first_id, last_id, has_more, object }
     */
    private fun handleItemsListResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        extractConversationId(response.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }

        val data = body["data"]
        val count = if (data is JsonArray) data.size.toLong() else 0L
        span.setAttribute("tracy.conversation.items.count", count)

        body["first_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.first_id", it)
        }
        body["last_id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.last_id", it)
        }
        body["has_more"]?.let {
            span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
        }
    }

    /**
     * Handles response for RETRIEVE route.
     *
     * Response schema: { id, type, status, ... }
     */
    private fun handleItemRetrieveResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.item.id", it)
        }
        body["type"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.item.type", it)
        }
        body["status"]?.jsonPrimitive?.content?.let { status ->
            val mappedStatus = if (status == "complete") "completed" else status
            span.setAttribute("tracy.conversation.item.status", mappedStatus)
        }
    }

    /**
     * Handles response for DELETE route.
     *
     * Extracts item ID and conversation ID from the URL path.
     */
    private fun handleItemDeleteResponse(span: Span, response: TracyHttpResponse) {
        extractItemId(response.url)?.let {
            span.setAttribute("tracy.conversation.item.id", it)
        }
        extractConversationId(response.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
    }

    /**
     * Detects which specific conversation items endpoint is being called based on URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ItemsRoute {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1) {
            logger.warn { "Failed to detect conversation items route. Endpoint has no `items` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ItemsRoute.LIST
        }
        val hasItemId = segments.size > (itemsIndex + 1) && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasItemId -> ItemsRoute.CREATE
            method == "GET" && hasItemId -> ItemsRoute.RETRIEVE
            method == "GET" && !hasItemId -> ItemsRoute.LIST
            method == "DELETE" && hasItemId -> ItemsRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation items route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ItemsRoute.LIST
            }
        }
    }

    /**
     * Extracts the conversation ID from a path like `/v1/conversations/{conversation_id}/items`.
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
            segments[conversationsIndex + 1].takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /**
     * Extracts the item ID from a path like `/v1/conversations/{conversation_id}/items/{item_id}`.
     */
    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        return if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
            segments[itemsIndex + 1].takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private enum class ItemsRoute {
        CREATE,   // POST /conversations/{conversation_id}/items
        LIST,     // GET /conversations/{conversation_id}/items
        RETRIEVE, // GET /conversations/{conversation_id}/items/{item_id}
        DELETE    // DELETE /conversations/{conversation_id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
