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
 * Handler for OpenAI Conversation Items API.
 *
 * The Conversation Items API provides endpoints for managing items within a conversation:
 * 1. `POST /conversations/{conversation_id}/items` - Create a conversation item
 * 2. `GET /conversations/{conversation_id}/items` - List conversation items
 * 3. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a conversation item
 * 4. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and delegates to the appropriate route handler.
 */
internal class ConversationItemsOpenAIApiEndpointHandler : EndpointApiHandler {
    private val routeHandlers: Map<ConversationItemRoute, ConversationItemRouteHandler> by lazy {
        mapOf(
            ConversationItemRoute.CREATE to ItemsCreateHandler(),
            ConversationItemRoute.LIST to ItemsListHandler(),
            ConversationItemRoute.RETRIEVE to ItemsRetrieveHandler(),
            ConversationItemRoute.DELETE to ItemsDeleteHandler()
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
        // Conversation Items API does not use server-sent events streaming
        logger.warn { "Conversation Items API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation items endpoint is being called based on URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationItemRoute {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1) {
            logger.warn { "Failed to detect conversation items route: no `items` segment in ${segments.joinToString("/")}" }
            return ConversationItemRoute.LIST
        }

        val hasItemId = segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasItemId -> ConversationItemRoute.CREATE
            method == "GET" && !hasItemId -> ConversationItemRoute.LIST
            method == "GET" && hasItemId -> ConversationItemRoute.RETRIEVE
            method == "DELETE" && hasItemId -> ConversationItemRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation items route: $method ${segments.joinToString("/")}" }
                ConversationItemRoute.LIST
            }
        }
    }

    private enum class ConversationItemRoute {
        CREATE,   // POST /conversations/{conversation_id}/items
        LIST,     // GET  /conversations/{conversation_id}/items
        RETRIEVE, // GET  /conversations/{conversation_id}/items/{item_id}
        DELETE    // DELETE /conversations/{conversation_id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
