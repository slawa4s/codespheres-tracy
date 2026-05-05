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

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides multiple endpoints for managing conversations and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `POST /conversations/{id}/items` - Add an item to a conversation
 * 3. `GET /conversations/{id}/items` - List items in a conversation
 * 4. `DELETE /conversations/{id}/items/{item_id}` - Delete an item from a conversation
 * 5. `DELETE /conversations/{id}` - Delete a conversation
 *
 * This handler detects the specific route and traces accordingly, setting
 * `gen_ai.operation.name` from URL structure in `handleRequestAttributes`
 * rather than deriving it from the response body's `object` field.
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
            ConversationRoute.ITEMS_CREATE to CreateConversationItemHandler(),
            ConversationRoute.ITEMS_LIST to ListConversationItemsHandler(),
            ConversationRoute.ITEMS_DELETE to DeleteConversationItemHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler()
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
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     *
     * Routes:
     * - `POST /conversations` → CREATE
     * - `POST /conversations/{id}/items` → ITEMS_CREATE
     * - `GET /conversations/{id}/items` → ITEMS_LIST
     * - `DELETE /conversations/{id}/items/{item_id}` → ITEMS_DELETE
     * - `DELETE /conversations/{id}` → DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank() &&
                segments[conversationsIndex + 1] != "conversations"

        val hasItems = segments.contains("items")

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "POST" && hasConversationId && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasConversationId && hasItems -> ConversationRoute.ITEMS_LIST
            method == "DELETE" && hasConversationId && hasItems -> ConversationRoute.ITEMS_DELETE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute {
        CREATE,        // POST /conversations
        ITEMS_CREATE,  // POST /conversations/{id}/items
        ITEMS_LIST,    // GET /conversations/{id}/items
        ITEMS_DELETE,  // DELETE /conversations/{id}/items/{item_id}
        DELETE         // DELETE /conversations/{id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
