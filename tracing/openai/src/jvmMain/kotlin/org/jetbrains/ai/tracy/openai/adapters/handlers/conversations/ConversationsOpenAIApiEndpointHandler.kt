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
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemDeleteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemRetrieveHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemsCreateHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationItemsListHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.CreateConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.DeleteConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.RetrieveConversationHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.UpdateConversationHandler

/**
 * Handler for the OpenAI Conversations API.
 *
 * Dispatches each request to a per-route handler based on URL path segments and HTTP method,
 * and sets `openai.api.type` = `"conversations"` on every span before dispatch.
 *
 * Supported routes:
 * - `POST /v1/conversations` → [ConversationRoute.CREATE]
 * - `GET /v1/conversations/{conversation_id}` → [ConversationRoute.RETRIEVE]
 * - `PATCH /v1/conversations/{conversation_id}` → [ConversationRoute.UPDATE]
 * - `DELETE /v1/conversations/{conversation_id}` → [ConversationRoute.DELETE]
 * - `POST /v1/conversations/{conversation_id}/items` → [ConversationRoute.ITEMS_CREATE]
 * - `GET /v1/conversations/{conversation_id}/items` → [ConversationRoute.ITEMS_LIST]
 * - `GET /v1/conversations/{conversation_id}/items/{item_id}` → [ConversationRoute.ITEMS_RETRIEVE]
 * - `DELETE /v1/conversations/{conversation_id}/items/{item_id}` → [ConversationRoute.ITEMS_DELETE]
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
            ConversationRoute.ITEMS_CREATE to ConversationItemsCreateHandler(),
            ConversationRoute.ITEMS_LIST to ConversationItemsListHandler(),
            ConversationRoute.ITEMS_RETRIEVE to ConversationItemRetrieveHandler(),
            ConversationRoute.ITEMS_DELETE to ConversationItemDeleteHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
        val route = detectRoute(request.url, request.method)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("openai.api.type", "conversations")
        val route = detectRoute(response.url, response.requestMethod)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific Conversations API endpoint is being called based on URL path and HTTP method.
     *
     * Detection logic:
     * 1. Locates the `conversations` segment in the path.
     * 2. Checks for a conversation ID segment immediately after.
     * 3. Checks for an `items` sub-path and an optional item ID segment.
     * 4. Disambiguates using the HTTP method.
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. No 'conversations' path segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()
        val hasItemsSegment = hasConversationId &&
                segments.size > conversationsIndex + 2 &&
                segments[conversationsIndex + 2] == "items"
        val hasItemId = hasItemsSegment &&
                segments.size > conversationsIndex + 3 &&
                segments[conversationsIndex + 3].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.RETRIEVE
            method == "PATCH" && hasConversationId && !hasItemsSegment -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE
            method == "POST" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * Distinguishes between the 8 Conversations API routes.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal enum class ConversationRoute {
    /** `POST /conversations` */
    CREATE,
    /** `GET /conversations/{conversation_id}` */
    RETRIEVE,
    /** `PATCH /conversations/{conversation_id}` */
    UPDATE,
    /** `DELETE /conversations/{conversation_id}` */
    DELETE,
    /** `POST /conversations/{conversation_id}/items` */
    ITEMS_CREATE,
    /** `GET /conversations/{conversation_id}/items` */
    ITEMS_LIST,
    /** `GET /conversations/{conversation_id}/items/{item_id}` */
    ITEMS_RETRIEVE,
    /** `DELETE /conversations/{conversation_id}/items/{item_id}` */
    ITEMS_DELETE,
}
