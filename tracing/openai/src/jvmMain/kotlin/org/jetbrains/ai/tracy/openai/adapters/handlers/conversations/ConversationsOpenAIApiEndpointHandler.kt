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
 * The Conversations API provides multiple endpoints for managing conversations and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `DELETE /conversations/{id}` - Delete a conversation
 * 3. `POST /conversations/{id}/items` - Create a conversation item
 * 4. `GET /conversations/{id}/items` - List conversation items
 * 5. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
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
            ConversationRoute.DELETE_CONVERSATION to DeleteConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateItemHandler(),
            ConversationRoute.LIST_ITEMS to ListItemsHandler(),
            ConversationRoute.DELETE_ITEM to DeleteItemHandler()
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
            return ConversationRoute.CREATE_CONVERSATION
        }

        val containsConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank() &&
                segments[conversationsIndex + 1] != "conversations"

        val containsItems = segments.contains("items")

        return when {
            method == "POST" && !containsConversationId -> ConversationRoute.CREATE_CONVERSATION
            method == "DELETE" && containsConversationId && !containsItems -> ConversationRoute.DELETE_CONVERSATION
            method == "POST" && containsConversationId && containsItems -> ConversationRoute.CREATE_ITEM
            method == "GET" && containsConversationId && containsItems -> ConversationRoute.LIST_ITEMS
            method == "DELETE" && containsConversationId && containsItems -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE_CONVERSATION
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    internal enum class ConversationRoute {
        CREATE_CONVERSATION,  // POST /conversations
        DELETE_CONVERSATION,  // DELETE /conversations/{id}
        CREATE_ITEM,          // POST /conversations/{id}/items
        LIST_ITEMS,           // GET /conversations/{id}/items
        DELETE_ITEM           // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
