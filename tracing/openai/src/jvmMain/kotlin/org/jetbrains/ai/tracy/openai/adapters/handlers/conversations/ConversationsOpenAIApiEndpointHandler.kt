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
 * The Conversations API provides endpoints for managing persistent conversation threads
 * and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 4. `POST /conversations/{conversation_id}/items` - Create a conversation item
 * 5. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a conversation item
 * 6. `GET /conversations/{conversation_id}/items` - List conversation items
 * 7. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
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
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateConversationItemHandler(),
            ConversationRoute.RETRIEVE_ITEM to RetrieveConversationItemHandler(),
            ConversationRoute.LIST_ITEMS to ListConversationItemsHandler(),
            ConversationRoute.DELETE_ITEM to DeleteConversationItemHandler()
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
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. No 'conversations' segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val containsConvId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()
        val containsItems = segments.contains("items")
        val itemsIndex = if (containsItems) segments.indexOf("items") else -1
        val containsItemId = containsItems && itemsIndex != -1 &&
                segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !containsConvId -> ConversationRoute.CREATE
            method == "GET" && containsConvId && !containsItems -> ConversationRoute.RETRIEVE
            method == "DELETE" && containsConvId && !containsItems -> ConversationRoute.DELETE
            method == "POST" && containsConvId && containsItems -> ConversationRoute.CREATE_ITEM
            method == "GET" && containsConvId && containsItems && containsItemId -> ConversationRoute.RETRIEVE_ITEM
            method == "GET" && containsConvId && containsItems && !containsItemId -> ConversationRoute.LIST_ITEMS
            method == "DELETE" && containsConvId && containsItems && containsItemId -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    internal enum class ConversationRoute {
        CREATE,         // POST /conversations
        RETRIEVE,       // GET /conversations/{conversation_id}
        DELETE,         // DELETE /conversations/{conversation_id}
        CREATE_ITEM,    // POST /conversations/{conversation_id}/items
        RETRIEVE_ITEM,  // GET /conversations/{conversation_id}/items/{item_id}
        LIST_ITEMS,     // GET /conversations/{conversation_id}/items
        DELETE_ITEM     // DELETE /conversations/{conversation_id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
