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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemDeleteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemRetrieveHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemsCreateHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemsListHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationRouteHandler

/**
 * Handler for the OpenAI Conversations API item operations.
 *
 * Routes HTTP requests to per-operation handlers:
 * - `POST   /conversations/{conversation_id}/items` → [ConversationItemsCreateHandler]
 * - `GET    /conversations/{conversation_id}/items` → [ConversationItemsListHandler]
 * - `GET    /conversations/{conversation_id}/items/{item_id}` → [ConversationItemRetrieveHandler]
 * - `DELETE /conversations/{conversation_id}/items/{item_id}` → [ConversationItemDeleteHandler]
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    /**
     * Registry of route handlers, initialized lazily to avoid creating handlers until needed.
     */
    private val routeHandlers: Map<ConversationItemsRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationItemsRoute.ITEMS_CREATE to ConversationItemsCreateHandler(),
            ConversationItemsRoute.ITEMS_LIST to ConversationItemsListHandler(),
            ConversationItemsRoute.ITEMS_RETRIEVE to ConversationItemRetrieveHandler(),
            ConversationItemsRoute.ITEMS_DELETE to ConversationItemDeleteHandler(),
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
     * Detects the specific conversation items route from the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationItemsRoute {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")

        if (itemsIndex == -1) {
            logger.warn { "Conversations handler invoked on path with no 'items' segment: ${segments.joinToString("/")}" }
            return ConversationItemsRoute.ITEMS_LIST
        }

        val hasItemId = segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank() &&
                segments[itemsIndex + 1] != "items"

        return when {
            method == "POST" && !hasItemId -> ConversationItemsRoute.ITEMS_CREATE
            method == "GET" && !hasItemId -> ConversationItemsRoute.ITEMS_LIST
            method == "GET" && hasItemId -> ConversationItemsRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItemId -> ConversationItemsRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation items route: $method ${segments.joinToString("/")}" }
                ConversationItemsRoute.ITEMS_LIST
            }
        }
    }

    private enum class ConversationItemsRoute {
        ITEMS_CREATE,    // POST   /conversations/{id}/items
        ITEMS_LIST,      // GET    /conversations/{id}/items
        ITEMS_RETRIEVE,  // GET    /conversations/{id}/items/{item_id}
        ITEMS_DELETE,    // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
