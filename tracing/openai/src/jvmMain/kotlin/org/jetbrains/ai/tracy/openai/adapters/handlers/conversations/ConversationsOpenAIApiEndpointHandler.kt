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
 * The Conversations API provides multiple endpoints for conversation operations:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `POST /conversations/{conversation_id}/items` - Create a conversation item
 * 3. `GET /conversations/{conversation_id}/items` - List conversation items with pagination
 * 4. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
 * 5. `DELETE /conversations/{conversation_id}` - Delete a conversation
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
            ConversationRoute.CREATE_CONVERSATION to CreateConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateItemHandler(),
            ConversationRoute.LIST_ITEMS to ListItemsHandler(),
            ConversationRoute.DELETE_ITEM to DeleteItemHandler(),
            ConversationRoute.DELETE_CONVERSATION to DeleteConversationHandler()
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
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE_CONVERSATION
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank() &&
                segments[conversationsIndex + 1] != "conversations"

        val hasItems = segments.contains("items")
        val itemsIndex = segments.indexOf("items")
        val hasItemId = hasItems && segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE_CONVERSATION
            method == "POST" && hasItems -> ConversationRoute.CREATE_ITEM
            method == "GET" && hasItems -> ConversationRoute.LIST_ITEMS
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.DELETE_ITEM
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE_CONVERSATION
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE_CONVERSATION
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    internal enum class ConversationRoute {
        CREATE_CONVERSATION,   // POST /conversations
        CREATE_ITEM,           // POST /conversations/{conversation_id}/items
        LIST_ITEMS,            // GET /conversations/{conversation_id}/items
        DELETE_ITEM,           // DELETE /conversations/{conversation_id}/items/{item_id}
        DELETE_CONVERSATION    // DELETE /conversations/{conversation_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
