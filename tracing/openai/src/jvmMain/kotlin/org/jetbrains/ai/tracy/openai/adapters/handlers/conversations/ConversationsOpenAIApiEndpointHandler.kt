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
 * 2. `GET /conversations/{id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{id}` - Delete a conversation
 * 4. `POST /conversations/{id}/items` - Add an item to a conversation
 * 5. `GET /conversations/{id}/items` - List items in a conversation
 * 6. `GET /conversations/{id}/items/{itemId}` - Retrieve a specific item
 * 7. `DELETE /conversations/{id}/items/{itemId}` - Delete a specific item
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
            ConversationRoute.ITEMS_CREATE to ItemsCreateHandler(),
            ConversationRoute.ITEMS_LIST to ItemsListHandler(),
            ConversationRoute.ITEMS_RETRIEVE to ItemsRetrieveHandler(),
            ConversationRoute.ITEMS_DELETE to ItemsDeleteHandler(),
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
        val convIndex = segments.indexOf("conversations")
        if (convIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasConvId = segments.size > convIndex + 1 && segments[convIndex + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIndex = if (hasItems) segments.indexOf("items") else -1
        val hasItemId = hasItems && itemsIndex != -1 &&
                segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConvId -> ConversationRoute.CREATE
            method == "GET" && hasConvId && !hasItems -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasConvId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "GET" && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
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
        RETRIEVE,       // GET /conversations/{id}
        DELETE,         // DELETE /conversations/{id}
        ITEMS_CREATE,   // POST /conversations/{id}/items
        ITEMS_LIST,     // GET /conversations/{id}/items
        ITEMS_RETRIEVE, // GET /conversations/{id}/items/{itemId}
        ITEMS_DELETE,   // DELETE /conversations/{id}/items/{itemId}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
