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
 * Handler for the OpenAI Conversations API.
 *
 * Routes requests to per-operation handlers based on the HTTP method and URL path:
 * 1. `POST /conversations` — Create a new conversation
 * 2. `GET /conversations/{conversation_id}` — Retrieve an existing conversation
 * 3. `POST /conversations/{conversation_id}` — Update conversation metadata
 * 4. `DELETE /conversations/{conversation_id}` — Delete a conversation
 *
 * Each route handler sets `gen_ai.operation.name` (e.g. `conversations.create`) and
 * `openai.api.type=conversations`, and extracts `gen_ai.conversation.id` and
 * `tracy.conversation.created_at` from the response body.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Internal enum to distinguish between different Conversations API routes.
     */
    internal enum class ConversationRoute {
        CREATE,    // POST /conversations
        RETRIEVE,  // GET /conversations/{conversation_id}
        UPDATE,    // POST /conversations/{conversation_id}
        DELETE,    // DELETE /conversations/{conversation_id}
    }

    /**
     * Registry of route handlers, initialized lazily to avoid creating handlers until needed.
     */
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
        // Conversations API does not use SSE streaming for lifecycle operations
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversations endpoint is being called based on the URL path and HTTP method.
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

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
