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
 * Supports the following endpoints:
 * 1. `POST /conversations` — Create a conversation
 * 2. `GET /conversations/{conversation_id}` — Retrieve a conversation
 * 3. `DELETE /conversations/{conversation_id}` — Delete a conversation
 * 4. `POST /conversations/{conversation_id}/items` — Create a conversation item
 * 5. `GET /conversations/{conversation_id}/items` — List conversation items
 * 6. `GET /conversations/{conversation_id}/items/{item_id}` — Retrieve a conversation item
 * 7. `DELETE /conversations/{conversation_id}/items/{item_id}` — Delete a conversation item
 *
 * Each route sets `gen_ai.operation.name` explicitly to override the value written by
 * [OpenAIApiUtils.setCommonResponseAttributes] from `body["object"]`, and always sets
 * `openai.api.type` to `"conversations"`.
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
     * Detects the specific conversations endpoint from the URL path and HTTP method.
     *
     * Detection logic:
     * - Locates the "conversations" segment in the path.
     * - Determines whether a conversation ID, "items" sub-path, and/or an item ID are present.
     * - Dispatches based on method + path shape.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route: no `conversations` segment in ${url.pathSegments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && itemsIndex > conversationsIndex

        val hasItemId = hasItems &&
                segments.size > (itemsIndex + 1) &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasItems && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Distinguishes between the supported Conversations API routes.
     */
    private enum class ConversationRoute {
        CREATE,          // POST /conversations
        RETRIEVE,        // GET  /conversations/{id}
        DELETE,          // DELETE /conversations/{id}
        ITEMS_CREATE,    // POST /conversations/{id}/items
        ITEMS_LIST,      // GET  /conversations/{id}/items
        ITEMS_RETRIEVE,  // GET  /conversations/{id}/items/{item_id}
        ITEMS_DELETE     // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
