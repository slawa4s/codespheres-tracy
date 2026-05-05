/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.*

/**
 * Handler for the OpenAI Conversations API.
 *
 * The Conversations API provides multiple endpoints for managing conversations and their items:
 * 1. `POST /conversations` — Create a new conversation
 * 2. `POST /conversations/{conversation_id}/items` — Create an item in a conversation
 * 3. `GET /conversations/{conversation_id}/items` — List items in a conversation
 * 4. `DELETE /conversations/{conversation_id}/items/{item_id}` — Delete a conversation item
 * 5. `DELETE /conversations/{conversation_id}` — Delete a conversation
 *
 * This handler detects the specific route and traces accordingly, setting:
 * - `openai.api.type` = `"conversations"`
 * - `gen_ai.operation.name` derived from the route (e.g. `conversations.create`)
 * - conversation- and item-specific span attributes
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
            ConversationRoute.CREATE_ITEM to CreateConversationItemHandler(),
            ConversationRoute.LIST_ITEMS to ListConversationItemsHandler(),
            ConversationRoute.DELETE_ITEM to DeleteConversationItemHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler()
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        // Override gen_ai.operation.name with the route-derived value so it reflects the actual
        // operation rather than whatever the response body "object" field may contain.
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversations endpoint is being called based on the URL path and HTTP method.
     *
     * Route discrimination rules:
     * - `POST /conversations`                                     → [ConversationRoute.CREATE]
     * - `POST /conversations/{id}/items`                         → [ConversationRoute.CREATE_ITEM]
     * - `GET  /conversations/{id}/items`                         → [ConversationRoute.LIST_ITEMS]
     * - `DELETE /conversations/{id}/items/{item_id}`             → [ConversationRoute.DELETE_ITEM]
     * - `DELETE /conversations/{id}`                             → [ConversationRoute.DELETE]
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route — no 'conversations' segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIndex = segments.indexOf("items")
        val hasItemId = hasItems && itemsIndex != -1 &&
                segments.size > (itemsIndex + 1) &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasId && !hasItems -> ConversationRoute.CREATE
            method == "POST" && hasItems -> ConversationRoute.CREATE_ITEM
            method == "GET" && hasItems -> ConversationRoute.LIST_ITEMS
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.DELETE_ITEM
            method == "DELETE" && hasId && !hasItems -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum distinguishing the different Conversations API routes.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        CREATE_ITEM("conversations.items.create"),
        LIST_ITEMS("conversations.items.list"),
        DELETE_ITEM("conversations.items.delete"),
        DELETE("conversations.delete")
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
