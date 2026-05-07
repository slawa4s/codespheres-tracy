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
 * The Conversations API provides endpoints for managing conversations and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conv_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conv_id}` - Update a conversation
 * 4. `DELETE /conversations/{conv_id}` - Delete a conversation
 * 5. `POST /conversations/{conv_id}/items` - Create a conversation item
 * 6. `GET /conversations/{conv_id}/items` - List conversation items
 * 7. `GET /conversations/{conv_id}/items/{item_id}` - Retrieve a conversation item
 * 8. `DELETE /conversations/{conv_id}/items/{item_id}` - Delete a conversation item
 *
 * Each route sets `gen_ai.operation.name` to a dotted value (e.g., `conversations.create`),
 * `openai.api.type` to `"conversations"`, and `gen_ai.conversation.id` from the URL path
 * or the response body `id` field.
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
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     *
     * Route detection is based on:
     * - Presence of `conv_id` path segment after `conversations`
     * - Presence of `items` path segment after `conv_id`
     * - Presence of `item_id` path segment after `items`
     * - HTTP method (POST/GET/DELETE)
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convIndex = segments.indexOf("conversations")
        if (convIndex == -1) {
            logger.warn { "Failed to detect conversation route. No `conversations` segment in: ${url.pathSegments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConvId = segments.size > convIndex + 1 &&
                segments[convIndex + 1].isNotBlank() &&
                segments[convIndex + 1] != "conversations"

        val hasItems = segments.contains("items")
        val itemsIndex = segments.indexOf("items")
        val hasItemId = hasItems && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank() &&
                segments[itemsIndex + 1] != "items"

        return when {
            // /conversations/{conv_id}/items/{item_id}
            hasItemId && method == "GET" -> ConversationRoute.ITEMS_RETRIEVE
            hasItemId && method == "DELETE" -> ConversationRoute.ITEMS_DELETE

            // /conversations/{conv_id}/items
            hasItems && method == "POST" -> ConversationRoute.ITEMS_CREATE
            hasItems && method == "GET" -> ConversationRoute.ITEMS_LIST

            // /conversations/{conv_id}
            hasConvId && method == "GET" -> ConversationRoute.RETRIEVE
            hasConvId && method == "POST" -> ConversationRoute.UPDATE
            hasConvId && method == "DELETE" -> ConversationRoute.DELETE

            // /conversations
            method == "POST" -> ConversationRoute.CREATE

            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute {
        CREATE,          // POST /conversations
        RETRIEVE,        // GET /conversations/{conv_id}
        UPDATE,          // POST /conversations/{conv_id}
        DELETE,          // DELETE /conversations/{conv_id}
        ITEMS_CREATE,    // POST /conversations/{conv_id}/items
        ITEMS_LIST,      // GET /conversations/{conv_id}/items
        ITEMS_RETRIEVE,  // GET /conversations/{conv_id}/items/{item_id}
        ITEMS_DELETE     // DELETE /conversations/{conv_id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
