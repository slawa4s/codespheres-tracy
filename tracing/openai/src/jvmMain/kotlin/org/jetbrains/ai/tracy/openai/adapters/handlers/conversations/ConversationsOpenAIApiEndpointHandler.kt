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
 * Handler for OpenAI Realtime Conversations API.
 *
 * The Conversations API provides endpoints for managing conversations and their items:
 * 1. `GET /realtime/conversations` - List all conversations (paginated)
 * 2. `GET /realtime/conversations/{conversation_id}` - Get a single conversation
 * 3. `DELETE /realtime/conversations/{conversation_id}` - Delete a conversation
 * 4. `GET /realtime/conversations/{conversation_id}/items` - List items in a conversation (paginated)
 * 5. `GET /realtime/conversations/{conversation_id}/items/{item_id}` - Get a single item
 * 6. `DELETE /realtime/conversations/{conversation_id}/items/{item_id}` - Delete an item
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Realtime API Reference](https://platform.openai.com/docs/api-reference/realtime)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.LIST_CONVERSATIONS to ListConversationsHandler(),
            ConversationRoute.GET_CONVERSATION to GetConversationHandler(),
            ConversationRoute.DELETE_CONVERSATION to DeleteConversationHandler(),
            ConversationRoute.LIST_ITEMS to ListConversationItemsHandler(),
            ConversationRoute.GET_ITEM to GetConversationItemHandler(),
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
     * Detects which specific conversation endpoint is being called based on URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route: no 'conversations' segment in ${segments.joinToString("/")}" }
            return ConversationRoute.LIST_CONVERSATIONS
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank() &&
                segments[conversationsIndex + 1] != "items"

        val hasItemsSegment = segments.contains("items")
        val itemsIndex = segments.indexOf("items")
        val hasItemId = hasItemsSegment && itemsIndex != -1 &&
                segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "GET" && !hasConversationId -> ConversationRoute.LIST_CONVERSATIONS
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.GET_CONVERSATION
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE_CONVERSATION
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.LIST_ITEMS
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.GET_ITEM
            method == "DELETE" && hasItemsSegment -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.LIST_CONVERSATIONS
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute {
        LIST_CONVERSATIONS,   // GET /realtime/conversations
        GET_CONVERSATION,     // GET /realtime/conversations/{conversation_id}
        DELETE_CONVERSATION,  // DELETE /realtime/conversations/{conversation_id}
        LIST_ITEMS,           // GET /realtime/conversations/{conversation_id}/items
        GET_ITEM,             // GET /realtime/conversations/{conversation_id}/items/{item_id}
        DELETE_ITEM           // DELETE /realtime/conversations/{conversation_id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
