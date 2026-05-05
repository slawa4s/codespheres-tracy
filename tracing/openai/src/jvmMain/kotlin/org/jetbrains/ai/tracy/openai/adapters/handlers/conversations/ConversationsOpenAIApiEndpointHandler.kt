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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.GetConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ListConversationsHandler

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides multiple endpoints for managing conversations:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations` - List all conversations
 * 3. `GET /conversations/{conversation_id}` - Retrieve a specific conversation
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 *
 * This handler detects the specific route and traces accordingly, setting
 * `gen_ai.operation.name` from URL structure in `handleRequestAttributes`
 * rather than deriving it from the response body's `object` field.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.LIST to ListConversationsHandler(),
            ConversationRoute.RETRIEVE to GetConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler()
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
     * Detects which specific conversation endpoint is being called based on URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. No 'conversations' segment in: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && !hasConversationId -> ConversationRoute.LIST
            method == "GET" && hasConversationId -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasConversationId -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    private enum class ConversationRoute {
        CREATE,    // POST /conversations
        LIST,      // GET /conversations
        RETRIEVE,  // GET /conversations/{conversation_id}
        DELETE     // DELETE /conversations/{conversation_id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
