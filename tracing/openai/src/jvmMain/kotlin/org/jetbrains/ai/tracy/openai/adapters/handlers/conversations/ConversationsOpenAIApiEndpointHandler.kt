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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.CreateConversationItemHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.DeleteConversationItemHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ListConversationItemsHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationItemHandler

/**
 * Handler for the OpenAI Conversations Items API.
 *
 * Routes requests to per-operation handlers based on HTTP method and URL path:
 * - `POST /conversations/{conversation_id}/items` → [ConversationItemRoute.CREATE_ITEM]
 * - `GET /conversations/{conversation_id}/items` → [ConversationItemRoute.LIST_ITEMS]
 * - `GET /conversations/{conversation_id}/items/{item_id}` → [ConversationItemRoute.RETRIEVE_ITEM]
 * - `DELETE /conversations/{conversation_id}/items/{item_id}` → [ConversationItemRoute.DELETE_ITEM]
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ConversationItemRoute, ConversationItemRouteHandler> by lazy {
        mapOf(
            ConversationItemRoute.CREATE_ITEM to CreateConversationItemHandler(),
            ConversationItemRoute.LIST_ITEMS to ListConversationItemsHandler(),
            ConversationItemRoute.RETRIEVE_ITEM to RetrieveConversationItemHandler(),
            ConversationItemRoute.DELETE_ITEM to DeleteConversationItemHandler(),
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
        logger.warn { "Conversations Items API does not use server-sent events streaming" }
    }

    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationItemRoute {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        val hasItemId = itemsIndex != -1 &&
                segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && itemsIndex != -1 -> ConversationItemRoute.CREATE_ITEM
            method == "GET" && itemsIndex != -1 && !hasItemId -> ConversationItemRoute.LIST_ITEMS
            method == "GET" && itemsIndex != -1 && hasItemId -> ConversationItemRoute.RETRIEVE_ITEM
            method == "DELETE" && itemsIndex != -1 -> ConversationItemRoute.DELETE_ITEM
            else -> {
                logger.warn { "Failed to detect conversation items route: $method ${segments.joinToString("/")}" }
                ConversationItemRoute.LIST_ITEMS
            }
        }
    }

    internal enum class ConversationItemRoute {
        CREATE_ITEM,
        LIST_ITEMS,
        RETRIEVE_ITEM,
        DELETE_ITEM,
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
