/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.*

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides multiple endpoints for conversation management:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` - Update a conversation
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 5. `POST /conversations/{conversation_id}/items` - Create an item in a conversation
 * 6. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve an item
 * 7. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete an item
 * 8. `GET /conversations/{conversation_id}/items` - List items in a conversation
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    /**
     * Registry of route handlers, initialized lazily to avoid creating handlers until needed.
     */
    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.UPDATE to UpdateConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateItemHandler(),
            ConversationRoute.RETRIEVE_ITEM to RetrieveItemHandler(),
            ConversationRoute.DELETE_ITEM to DeleteItemHandler(),
            ConversationRoute.LIST_ITEMS to ListItemsHandler()
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

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
                segments[conversationsIndex + 1].isNotBlank() &&
                segments[conversationsIndex + 1] != "conversations"

        val containsItems = segments.contains("items")

        val hasItemId = containsItems &&
                segments.indexOf("items").let { itemsIdx ->
                    itemsIdx != -1 && segments.size > (itemsIdx + 1) &&
                            segments[itemsIdx + 1].isNotBlank()
                }

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !containsItems -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !containsItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !containsItems -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && containsItems -> ConversationRoute.CREATE_ITEM
            method == "GET" && hasConversationId && containsItems && hasItemId -> ConversationRoute.RETRIEVE_ITEM
            method == "DELETE" && hasConversationId && containsItems -> ConversationRoute.DELETE_ITEM
            method == "GET" && hasConversationId && containsItems && !hasItemId -> ConversationRoute.LIST_ITEMS
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
        CREATE,          // POST /conversations
        RETRIEVE,        // GET /conversations/{conversation_id}
        UPDATE,          // POST /conversations/{conversation_id}
        DELETE,          // DELETE /conversations/{conversation_id}
        CREATE_ITEM,     // POST /conversations/{conversation_id}/items
        RETRIEVE_ITEM,   // GET /conversations/{conversation_id}/items/{item_id}
        DELETE_ITEM,     // DELETE /conversations/{conversation_id}/items/{item_id}
        LIST_ITEMS       // GET /conversations/{conversation_id}/items
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
