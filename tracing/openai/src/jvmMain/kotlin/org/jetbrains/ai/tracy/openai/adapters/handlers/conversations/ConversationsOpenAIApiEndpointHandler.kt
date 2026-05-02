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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationItemHandler

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing conversations and conversation items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{id}` - Delete a conversation
 * 4. `POST /conversations/{id}/items` - Create a conversation item
 * 5. `GET /conversations/{id}/items` - List conversation items
 * 6. `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * 7. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * Sets `openai.api.type = "conversations"` on every span in [handleRequestAttributes], and
 * overrides `gen_ai.operation.name` with the correct semantic operation name per route
 * (called after [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 * in the adapter, so it always wins).
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.ITEMS_CREATE to CreateConversationItemHandler(),
            ConversationRoute.ITEMS_LIST to ListConversationItemsHandler(),
            ConversationRoute.ITEMS_RETRIEVE to RetrieveConversationItemHandler(),
            ConversationRoute.ITEMS_DELETE to DeleteConversationItemHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
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
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()
        val hasItems = hasId && segments.contains("items")
        val hasItemId = if (hasItems) {
            val itemsIndex = segments.indexOf("items")
            segments.size > (itemsIndex + 1) && segments[itemsIndex + 1].isNotBlank()
        } else {
            false
        }

        return when {
            method == "POST" && !hasId -> ConversationRoute.CREATE
            method == "GET" && hasId && !hasItems -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasId && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasId && hasItems -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different Conversations API routes.
     */
    private enum class ConversationRoute {
        CREATE,          // POST /conversations
        RETRIEVE,        // GET /conversations/{id}
        DELETE,          // DELETE /conversations/{id}
        ITEMS_CREATE,    // POST /conversations/{id}/items
        ITEMS_LIST,      // GET /conversations/{id}/items
        ITEMS_RETRIEVE,  // GET /conversations/{id}/items/{item_id}
        ITEMS_DELETE,    // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
