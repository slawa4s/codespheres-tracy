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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.CreateConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.CreateConversationItemHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.DeleteConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.DeleteConversationItemHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ListConversationItemsHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationItemHandler

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides multiple endpoints for conversation operations:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `DELETE /conversations/{id}` - Delete a conversation
 * 3. `POST /conversations/{id}/items` - Create a conversation item
 * 4. `GET /conversations/{id}/items` - List conversation items
 * 5. `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * 6. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and traces accordingly.
 * Each route sets `gen_ai.operation.name` explicitly from the URL+method rather than relying on
 * the response `object` field, which gives wrong values like `conversation` or `list`.
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
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.ITEMS_CREATE to CreateConversationItemHandler(),
            ConversationRoute.ITEMS_LIST to ListConversationItemsHandler(),
            ConversationRoute.ITEMS_RETRIEVE to RetrieveConversationItemHandler(),
            ConversationRoute.ITEMS_DELETE to DeleteConversationItemHandler()
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
     *
     * Routes:
     * - POST /conversations                          → CREATE
     * - DELETE /conversations/{id}                   → DELETE
     * - POST /conversations/{id}/items               → ITEMS_CREATE
     * - GET /conversations/{id}/items                → ITEMS_LIST
     * - GET /conversations/{id}/items/{item_id}      → ITEMS_RETRIEVE
     * - DELETE /conversations/{id}/items/{item_id}   → ITEMS_DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. No `conversations` path segment: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIndex = segments.indexOf("items")
        val hasItemId = hasItems && segments.size > (itemsIndex + 1) &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different Conversations API routes.
     */
    internal enum class ConversationRoute {
        CREATE,          // POST /conversations
        DELETE,          // DELETE /conversations/{id}
        ITEMS_CREATE,    // POST /conversations/{id}/items
        ITEMS_LIST,      // GET /conversations/{id}/items
        ITEMS_RETRIEVE,  // GET /conversations/{id}/items/{item_id}
        ITEMS_DELETE     // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
