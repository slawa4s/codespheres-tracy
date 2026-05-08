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
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
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
 * The Conversations API provides endpoints for managing conversation threads and their items:
 * 1. `POST /conversations` - Create a conversation
 * 2. `GET /conversations/{id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{id}` - Delete a conversation
 * 4. `POST /conversations/{id}/items` - Create a conversation item
 * 5. `GET /conversations/{id}/items` - List conversation items
 * 6. `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * 7. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and traces accordingly, setting
 * `gen_ai.operation.name` and `openai.api.type` on each span.
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
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.ITEMS_CREATE to CreateConversationItemHandler(),
            ConversationRoute.ITEMS_LIST to ListConversationItemsHandler(),
            ConversationRoute.ITEMS_RETRIEVE to RetrieveConversationItemHandler(),
            ConversationRoute.ITEMS_DELETE to DeleteConversationItemHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        span.setAttribute("openai.api.type", "conversations")
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
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
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()
        val itemsIndex = segments.indexOf("items")
        val hasItemsSegment = itemsIndex != -1
        val hasItemId = hasItemsSegment && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE
            method == "POST" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    internal enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),             // POST /conversations
        RETRIEVE("conversations.retrieve"),         // GET /conversations/{id}
        DELETE("conversations.delete"),             // DELETE /conversations/{id}
        ITEMS_CREATE("conversations.items.create"), // POST /conversations/{id}/items
        ITEMS_LIST("conversations.items.list"),     // GET /conversations/{id}/items
        ITEMS_RETRIEVE("conversations.items.retrieve"), // GET /conversations/{id}/items/{item_id}
        ITEMS_DELETE("conversations.items.delete"), // DELETE /conversations/{id}/items/{item_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
