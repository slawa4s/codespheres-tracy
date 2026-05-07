/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
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
 * The Conversations API provides multiple endpoints for managing persistent conversations:
 * 1. `POST /conversations` — Create a new conversation
 * 2. `GET /conversations/{id}` — Retrieve a conversation
 * 3. `POST /conversations/{id}` — Update a conversation
 * 4. `DELETE /conversations/{id}` — Delete a conversation
 * 5. `POST /conversations/{id}/items` — Add an item to a conversation
 * 6. `GET /conversations/{id}/items` — List items in a conversation (with pagination)
 * 7. `GET /conversations/{id}/items/{item_id}` — Retrieve a specific item
 * 8. `DELETE /conversations/{id}/items/{item_id}` — Delete a specific item
 *
 * This handler detects the specific route and sets telemetry attributes accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Represents the distinct routes supported by the Conversations API.
     * Each route maps to an [operationName] used for `gen_ai.operation.name`.
     */
    enum class ConversationRoute(val operationName: String) {
        /** POST /conversations */
        CREATE("conversations.create"),

        /** GET /conversations/{id} */
        RETRIEVE("conversations.retrieve"),

        /** POST /conversations/{id} */
        UPDATE("conversations.update"),

        /** DELETE /conversations/{id} */
        DELETE("conversations.delete"),

        /** POST /conversations/{id}/items */
        ITEMS_CREATE("conversations.items.create"),

        /** GET /conversations/{id}/items */
        ITEMS_LIST("conversations.items.list"),

        /** GET /conversations/{id}/items/{item_id} */
        ITEMS_RETRIEVE("conversations.items.retrieve"),

        /** DELETE /conversations/{id}/items/{item_id} */
        ITEMS_DELETE("conversations.items.delete"),
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        span.setAttribute("openai.api.type", "conversations")

        val conversationId = extractConversationId(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        }

        when (route) {
            ConversationRoute.ITEMS_LIST -> {
                val params = request.url.parameters
                params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
                params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            }

            ConversationRoute.ITEMS_RETRIEVE, ConversationRoute.ITEMS_DELETE -> {
                val itemId = extractItemId(request.url)
                if (itemId != null) {
                    span.setAttribute("tracy.conversation.item.id", itemId)
                }
            }

            else -> {}
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val route = detectRoute(response.url, response.requestMethod)
        // Override the value set by setCommonResponseAttributes so the span reflects
        // the per-route operation name rather than the generic "object" field value.
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE, ConversationRoute.UPDATE -> {
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.DELETE -> {
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["deleted"]?.let { span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
            }

            ConversationRoute.ITEMS_CREATE, ConversationRoute.ITEMS_LIST -> {
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                }
                body["first_id"]?.let {
                    span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content)
                }
                body["last_id"]?.let {
                    span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content)
                }
                body["has_more"]?.let {
                    span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
                }
            }

            ConversationRoute.ITEMS_RETRIEVE -> {
                // ConversationItem is a union — extract common fields present across variants
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }

            ConversationRoute.ITEMS_DELETE -> {
                // Deleting an item returns the updated Conversation object
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not support server-sent events streaming" }
    }

    /**
     * Detects which specific Conversations API route is being called based on the URL path and HTTP method.
     *
     * Routes:
     * - `POST /conversations` → [ConversationRoute.CREATE]
     * - `GET /conversations/{id}` → [ConversationRoute.RETRIEVE]
     * - `POST /conversations/{id}` → [ConversationRoute.UPDATE]
     * - `DELETE /conversations/{id}` → [ConversationRoute.DELETE]
     * - `POST /conversations/{id}/items` → [ConversationRoute.ITEMS_CREATE]
     * - `GET /conversations/{id}/items` → [ConversationRoute.ITEMS_LIST]
     * - `GET /conversations/{id}/items/{item_id}` → [ConversationRoute.ITEMS_RETRIEVE]
     * - `DELETE /conversations/{id}/items/{item_id}` → [ConversationRoute.ITEMS_DELETE]
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversations route. No 'conversations' segment in: ${
                    url.pathSegments.joinToString(separator = "/")
                }"
            }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItemsSegment = itemsIndex != -1 && itemsIndex > conversationsIndex
        val hasItemId = hasItemsSegment &&
                segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !hasItemsSegment -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE
            method == "POST" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn {
                    "Failed to detect conversations route: $method ${
                        url.pathSegments.joinToString(separator = "/")
                    }"
                }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Extracts the conversation ID from a path like `/v1/conversations/{id}` or
     * `/v1/conversations/{id}/items`.
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val potentialId = segments[conversationsIndex + 1]
        return if (potentialId.isNotBlank() && potentialId != "items") potentialId else null
    }

    /**
     * Extracts the item ID from a path like `/v1/conversations/{id}/items/{item_id}`.
     */
    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
        val potentialId = segments[itemsIndex + 1]
        return if (potentialId.isNotBlank()) potentialId else null
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
