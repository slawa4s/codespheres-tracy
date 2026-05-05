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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.DeleteConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.UpdateConversationHandler

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing multi-turn conversation state:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` - Update an existing conversation
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 *
 * Each sub-handler sets `gen_ai.operation.name` at request time from the HTTP method and
 * path pattern — not from the response body's generic "object" field.
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
     * Detects the conversation route from URL path and HTTP method.
     *
     * Route detection rules:
     * - `POST /conversations` (no ID) → [ConversationRoute.CREATE]
     * - `GET /conversations/{id}` → [ConversationRoute.RETRIEVE]
     * - `POST /conversations/{id}` → [ConversationRoute.UPDATE]
     * - `DELETE /conversations/{id}` → [ConversationRoute.DELETE]
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    private enum class ConversationRoute {
        CREATE,    // POST /conversations
        RETRIEVE,  // GET /conversations/{conversation_id}
        UPDATE,    // POST /conversations/{conversation_id}
        DELETE,    // DELETE /conversations/{conversation_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
