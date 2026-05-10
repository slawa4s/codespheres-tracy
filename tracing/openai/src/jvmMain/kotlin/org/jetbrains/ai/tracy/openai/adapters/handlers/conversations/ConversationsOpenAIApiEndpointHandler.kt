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
 * Dispatches to one of four route-specific handlers based on the HTTP method and
 * whether a conversation ID is present in the URL path:
 *
 * | Route            | Method | Path                              |
 * |------------------|--------|-----------------------------------|
 * | CREATE           | POST   | `/v1/conversations`               |
 * | RETRIEVE         | GET    | `/v1/conversations/{id}`          |
 * | UPDATE           | POST   | `/v1/conversations/{id}`          |
 * | DELETE           | DELETE | `/v1/conversations/{id}`          |
 *
 * Each route handler sets `gen_ai.operation.name` to `conversations.{verb}`,
 * `gen_ai.conversation.id` from the conversation ID, and additional lifecycle
 * attributes such as `tracy.conversation.created_at` and `tracy.conversation.deleted`.
 *
 * See: [OpenAI Conversations API Reference](https://platform.openai.com/docs/api-reference)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.UPDATE to UpdateConversationHandler(),
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

    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val hasConversationId = extractConversationIdFromPath(url) != null

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId -> ConversationRoute.RETRIEVE
            (method == "POST" || method == "PATCH") && hasConversationId -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    private enum class ConversationRoute {
        CREATE,   // POST /conversations
        RETRIEVE, // GET /conversations/{id}
        UPDATE,   // POST /conversations/{id}
        DELETE    // DELETE /conversations/{id}
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
