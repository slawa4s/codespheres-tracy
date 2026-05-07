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

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing conversations and their items:
 * 1. `POST /conversations` - Create a conversation
 * 2. `GET /conversations/{id}` - Retrieve a conversation
 * 3. `PATCH /conversations/{id}` - Update a conversation
 * 4. `DELETE /conversations/{id}` - Delete a conversation
 * 5. `POST /conversations/{id}/items` - Create conversation items (paginated)
 * 6. `GET /conversations/{id}/items` - List conversation items (paginated)
 * 7. `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * 8. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route from path segments and HTTP method, then
 * traces accordingly. For list and paginated-create requests, query parameters
 * (`limit`, `order`, `after`) are read and recorded on the span.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("gen_ai.request.operation", route.operationName)

        // For list and paginated-create requests, read pagination query parameters
        if (route == ConversationRoute.ITEMS_LIST || route == ConversationRoute.ITEMS_CREATE) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Response attribute extraction is not defined for the Conversations API handler
    }

    override fun handleStreaming(span: Span, events: String) {
        // SSE streaming is not handled for the Conversations API
    }

    /**
     * Detects which specific conversations endpoint is being called based on URL path segments
     * and the HTTP method.
     *
     * URL structure:
     * - `/conversations` — no conversation ID after the segment
     * - `/conversations/{id}` — has a conversation ID, no further segments
     * - `/conversations/{id}/items` — has a conversation ID and "items" segment
     * - `/conversations/{id}/items/{item_id}` — has a conversation ID, "items", and an item ID
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversations route. No 'conversations' segment in: ${segments.joinToString("/")}"
            }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()
        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1
        val hasItemId = hasItems &&
                segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            method == "PATCH" && hasConversationId && !hasItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversations route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        RETRIEVE("conversations.retrieve"),
        UPDATE("conversations.update"),
        DELETE("conversations.delete"),
        ITEMS_CREATE("conversations.items.create"),
        ITEMS_LIST("conversations.items.list"),
        ITEMS_RETRIEVE("conversations.items.retrieve"),
        ITEMS_DELETE("conversations.items.delete"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
