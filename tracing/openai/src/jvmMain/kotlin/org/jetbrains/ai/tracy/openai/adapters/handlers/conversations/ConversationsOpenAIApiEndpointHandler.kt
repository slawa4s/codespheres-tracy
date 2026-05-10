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
 * Handler for the OpenAI Conversations API (`/v1/conversations/...`).
 *
 * Routes all eight Conversations endpoints to dedicated per-route handlers and sets
 * `openai.api.type = "conversations"` and the correct `gen_ai.operation.name` on every span.
 *
 * Supported routes:
 * 1. `POST /conversations` → `conversations.create`
 * 2. `GET /conversations/{id}` → `conversations.retrieve`
 * 3. `POST /conversations/{id}` → `conversations.update`
 * 4. `DELETE /conversations/{id}` → `conversations.delete`
 * 5. `POST /conversations/{id}/items` → `conversations.items.create`
 * 6. `GET /conversations/{id}/items` → `conversations.items.list`
 * 7. `GET /conversations/{id}/items/{item_id}` → `conversations.items.retrieve`
 * 8. `DELETE /conversations/{id}/items/{item_id}` → `conversations.items.delete`
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
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
    }

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

    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        RETRIEVE("conversations.retrieve"),
        UPDATE("conversations.update"),
        DELETE("conversations.delete"),
        CREATE_ITEM("conversations.items.create"),
        LIST_ITEMS("conversations.items.list"),
        RETRIEVE_ITEM("conversations.items.retrieve"),
        DELETE_ITEM("conversations.items.delete"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
