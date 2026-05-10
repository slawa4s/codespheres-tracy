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
 * Handler for the OpenAI Conversations API.
 *
 * Routes all eight Conversations endpoints to dedicated per-route handlers:
 * 1. `POST /conversations` — create conversation
 * 2. `GET /conversations/{id}` — retrieve conversation
 * 3. `POST /conversations/{id}` — update conversation
 * 4. `DELETE /conversations/{id}` — delete conversation
 * 5. `POST /conversations/{id}/items` — create conversation items
 * 6. `GET /conversations/{id}/items` — list conversation items
 * 7. `GET /conversations/{id}/items/{item_id}` — retrieve conversation item
 * 8. `DELETE /conversations/{id}/items/{item_id}` — delete conversation item
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.UPDATE to UpdateConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateConversationItemHandler(),
            ConversationRoute.LIST_ITEMS to ListConversationItemsHandler(),
            ConversationRoute.RETRIEVE_ITEM to RetrieveConversationItemHandler(),
            ConversationRoute.DELETE_ITEM to DeleteConversationItemHandler(),
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
    }

    /**
     * Determines the specific Conversations route from URL segments and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convIdx = segments.indexOf("conversations")
        if (convIdx == -1) {
            logger.warn { "No 'conversations' segment in URL: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > convIdx + 1 &&
                segments[convIdx + 1].isNotBlank() &&
                segments[convIdx + 1] != "items"

        val itemsIdx = segments.indexOf("items")
        val hasItems = itemsIdx != -1
        val hasItemId = hasItems && segments.size > itemsIdx + 1 && segments[itemsIdx + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !hasItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasItems && !hasItemId -> ConversationRoute.CREATE_ITEM
            method == "GET" && hasItems && !hasItemId -> ConversationRoute.LIST_ITEMS
            method == "GET" && hasItems && hasItemId -> ConversationRoute.RETRIEVE_ITEM
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.DELETE_ITEM
            else -> {
                logger.warn { "Unrecognised Conversations route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    private enum class ConversationRoute {
        CREATE,
        RETRIEVE,
        UPDATE,
        DELETE,
        CREATE_ITEM,
        LIST_ITEMS,
        RETRIEVE_ITEM,
        DELETE_ITEM,
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
