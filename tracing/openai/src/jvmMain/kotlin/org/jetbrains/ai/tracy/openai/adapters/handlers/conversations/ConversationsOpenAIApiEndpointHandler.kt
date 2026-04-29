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

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing conversations and their items:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{id}` - Delete a conversation
 * 4. `POST /conversations/{id}/items` - Create a conversation item
 * 5. `GET /conversations/{id}/items` - List conversation items
 * 6. `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * 7. `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and sets the correct [GEN_AI_OPERATION_NAME].
 * It deliberately does NOT rely on [OpenAIApiUtils.setCommonResponseAttributes] to set
 * the operation name, because that method overwrites it with the response body's `object`
 * field (e.g. `"conversation"`, `"list"`, `"conversation.deleted"`), producing wrong values.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute("gen_ai.provider.name", "openai")
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-set gen_ai.operation.name to override the value written by the adapter's
        // setCommonResponseAttributes call, which incorrectly uses the response body's
        // 'object' field (e.g. 'conversation', 'list', 'conversation.deleted').
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     *
     * Route shapes (segments after the `conversations` index):
     * - 0 remaining, POST  → CREATE
     * - 1 remaining, GET   → RETRIEVE
     * - 1 remaining, DELETE → DELETE
     * - 2 remaining, POST  → ITEMS_CREATE
     * - 2 remaining, GET   → ITEMS_LIST
     * - 3 remaining, GET   → ITEMS_RETRIEVE
     * - 3 remaining, DELETE → ITEMS_DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. No 'conversations' segment in: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val remaining = segments.size - conversationsIndex - 1

        return when {
            remaining == 0 && method == "POST" -> ConversationRoute.CREATE
            remaining == 1 && method == "GET" -> ConversationRoute.RETRIEVE
            remaining == 1 && method == "DELETE" -> ConversationRoute.DELETE
            remaining == 2 && method == "POST" -> ConversationRoute.ITEMS_CREATE
            remaining == 2 && method == "GET" -> ConversationRoute.ITEMS_LIST
            remaining == 3 && method == "GET" -> ConversationRoute.ITEMS_RETRIEVE
            remaining == 3 && method == "DELETE" -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between the different Conversations API routes.
     *
     * @property operationName The value written to the [GEN_AI_OPERATION_NAME] span attribute.
     */
    private enum class ConversationRoute(val operationName: String) {
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
