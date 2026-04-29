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
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing persistent conversations and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 4. `POST /conversations/{conversation_id}/items` - Create items in a conversation
 * 5. `GET /conversations/{conversation_id}/items` - List items in a conversation
 * 6. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a conversation item
 * 7. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and sets the correct [GEN_AI_OPERATION_NAME].
 * It deliberately does NOT rely on [OpenAIApiUtils.setCommonResponseAttributes] to set
 * the operation name, because that method overwrites it with the response body's `object`
 * field (e.g. `"conversation"`, `"list"`, `"conversation.deleted"`), producing wrong values.
 * The operation name is therefore re-set in [handleResponseAttributes] to override that.
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
            ConversationRoute.ITEMS_CREATE to ItemsCreateHandler(),
            ConversationRoute.ITEMS_LIST to ItemsListHandler(),
            ConversationRoute.ITEMS_RETRIEVE to ItemsRetrieveHandler(),
            ConversationRoute.ITEMS_DELETE to ItemsDeleteHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute("gen_ai.provider.name", "openai")
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-set gen_ai.operation.name to override the value written by the adapter's
        // setCommonResponseAttributes call, which incorrectly uses the response body's
        // 'object' field (e.g. 'conversation', 'list', 'conversation.deleted').
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
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
            logger.warn { "Failed to detect conversation route: no 'conversations' segment in ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val afterConversations = if (segments.size > conversationsIndex + 1) segments[conversationsIndex + 1] else ""
        val hasConversationId = afterConversations.isNotBlank() && afterConversations != "items"

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1

        val afterItems = if (hasItems && segments.size > itemsIndex + 1) segments[itemsIndex + 1] else ""
        val hasItemId = hasItems && afterItems.isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     *
     * @property operationName The value written to the [GEN_AI_OPERATION_NAME] span attribute.
     */
    internal enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        RETRIEVE("conversations.retrieve"),
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
