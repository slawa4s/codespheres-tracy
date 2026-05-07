/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API.
 *
 * Routes:
 * - `POST /conversations` → conversations.create
 * - `GET /conversations/{id}` → conversations.retrieve
 * - `POST /conversations/{id}` or `PATCH /conversations/{id}` → conversations.update
 * - `DELETE /conversations/{id}` → conversations.delete
 * - `POST /conversations/{id}/items` → conversations.items.create
 * - `GET /conversations/{id}/items` → conversations.items.list
 * - `GET /conversations/{id}/items/{item_id}` → conversations.items.retrieve
 * - `DELETE /conversations/{id}/items/{item_id}` → conversations.items.delete
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // No additional request attributes beyond common ones for conversations routes
    }

    /**
     * Overrides the generic `gen_ai.operation.name` set by [OpenAIApiUtils.setCommonResponseAttributes]
     * with a route-specific value, then sets `openai.api.type = "conversations"` on every route.
     *
     * For conversation-object responses (create/retrieve/update):
     * - Sets `gen_ai.conversation.id` from the `id` field
     * - Sets `tracy.conversation.created_at` from the `created_at` field
     *
     * For delete-conversation responses:
     * - Sets `tracy.conversation.deleted` from the `deleted` field
     * - Sets `gen_ai.conversation.id` from the `id` field
     */
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)

        // Override gen_ai.operation.name with the route-specific value
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        // Set openai.api.type on every route
        span.setAttribute("openai.api.type", "conversations")

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            ConversationRoute.CREATE,
            ConversationRoute.RETRIEVE,
            ConversationRoute.UPDATE -> {
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }
            ConversationRoute.DELETE -> {
                body["deleted"]?.let { span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
            }
            ConversationRoute.ITEMS_CREATE,
            ConversationRoute.ITEMS_LIST,
            ConversationRoute.ITEMS_RETRIEVE,
            ConversationRoute.ITEMS_DELETE -> {
                // No additional attributes for item routes beyond operation name and api type
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route: no `conversations` path segment in ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && itemsIndex > conversationsIndex

        val hasItemId = hasItems && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            hasItems && hasItemId && method == "GET" -> ConversationRoute.ITEMS_RETRIEVE
            hasItems && hasItemId && method == "DELETE" -> ConversationRoute.ITEMS_DELETE
            hasItems && method == "POST" -> ConversationRoute.ITEMS_CREATE
            hasItems && method == "GET" -> ConversationRoute.ITEMS_LIST
            hasConversationId && method == "GET" -> ConversationRoute.RETRIEVE
            hasConversationId && (method == "POST" || method == "PATCH") -> ConversationRoute.UPDATE
            hasConversationId && method == "DELETE" -> ConversationRoute.DELETE
            method == "POST" -> ConversationRoute.CREATE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
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
