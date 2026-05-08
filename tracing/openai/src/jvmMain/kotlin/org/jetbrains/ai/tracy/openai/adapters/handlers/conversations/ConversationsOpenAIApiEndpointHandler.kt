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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.UpdateConversationHandler

/**
 * Handler for the OpenAI Conversations API.
 *
 * Detects which specific Conversations endpoint is being called from the URL path and HTTP method,
 * then delegates attribute extraction to the appropriate [ConversationRouteHandler].
 *
 * Supported routes:
 * 1. `POST /v1/conversations` — create a conversation
 * 2. `GET /v1/conversations/{conversation_id}` — retrieve a conversation
 * 3. `POST /v1/conversations/{conversation_id}` — update a conversation
 * 4. `DELETE /v1/conversations/{conversation_id}` — delete a conversation
 * 5. `POST /v1/conversations/{conversation_id}/items` — create a conversation item
 * 6. `GET /v1/conversations/{conversation_id}/items` — list conversation items
 * 7. `GET /v1/conversations/{conversation_id}/items/{item_id}` — retrieve a conversation item
 * 8. `DELETE /v1/conversations/{conversation_id}/items/{item_id}` — delete a conversation item
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /** Routes within the Conversations API, distinguished by URL path shape and HTTP method. */
    internal enum class ConversationRoute {
        CREATE,         // POST  /conversations
        RETRIEVE,       // GET   /conversations/{conversation_id}
        UPDATE,         // POST  /conversations/{conversation_id}
        DELETE,         // DELETE /conversations/{conversation_id}
        CREATE_ITEM,    // POST  /conversations/{conversation_id}/items
        LIST_ITEMS,     // GET   /conversations/{conversation_id}/items
        RETRIEVE_ITEM,  // GET   /conversations/{conversation_id}/items/{item_id}
        DELETE_ITEM     // DELETE /conversations/{conversation_id}/items/{item_id}
    }

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
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Determines the [ConversationRoute] from [url] path segments and HTTP [method].
     *
     * Decision tree:
     * - If `"items"` segment is present after `"conversations"`:
     *   - segment after `"items"` present → item-level operation (RETRIEVE_ITEM / DELETE_ITEM)
     *   - no segment after `"items"` → collection-level item operation (LIST_ITEMS / CREATE_ITEM)
     * - Otherwise:
     *   - segment after `"conversations"` present → conversation-level operation (RETRIEVE / UPDATE / DELETE)
     *   - no segment after `"conversations"` → CREATE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversation route — no 'conversations' path segment: " +
                        segments.joinToString("/")
            }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && itemsIndex > conversationsIndex
        val hasItemId = hasItems && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            hasItems && hasItemId && method == "GET" -> ConversationRoute.RETRIEVE_ITEM
            hasItems && hasItemId && method == "DELETE" -> ConversationRoute.DELETE_ITEM
            hasItems && !hasItemId && method == "GET" -> ConversationRoute.LIST_ITEMS
            hasItems && !hasItemId && method == "POST" -> ConversationRoute.CREATE_ITEM
            !hasConversationId && method == "POST" -> ConversationRoute.CREATE
            hasConversationId && !hasItems && method == "GET" -> ConversationRoute.RETRIEVE
            hasConversationId && !hasItems && method == "POST" -> ConversationRoute.UPDATE
            hasConversationId && !hasItems && method == "DELETE" -> ConversationRoute.DELETE
            else -> {
                logger.warn {
                    "Failed to detect conversation route: $method ${segments.joinToString("/")}"
                }
                ConversationRoute.CREATE
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
