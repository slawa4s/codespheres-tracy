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
 * The Conversations API provides endpoints for managing persistent conversations
 * and their items:
 *
 * ### Conversation-level endpoints
 * - `POST /conversations` - Create a new conversation
 * - `GET /conversations/{id}` - Retrieve a conversation
 * - `PATCH /conversations/{id}` - Update a conversation
 * - `DELETE /conversations/{id}` - Delete a conversation
 *
 * ### Item-level endpoints
 * - `POST /conversations/{id}/items` - Create a conversation item
 * - `GET /conversations/{id}/items` - List conversation items
 * - `GET /conversations/{id}/items/{item_id}` - Retrieve a conversation item
 * - `DELETE /conversations/{id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and traces accordingly, emitting
 * `openai.api.type = "conversations"` and a route-specific `gen_ai.operation.name`.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")

        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        // Extract conversation ID from the URL path (present for all routes except CREATE)
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }

        // Extract item ID from the URL path for item-level single-resource routes
        if (route == ConversationRoute.ITEMS_RETRIEVE || route == ConversationRoute.ITEMS_DELETE) {
            extractItemIdFromPath(request.url)?.let {
                span.setAttribute("tracy.conversation.item.id", it)
            }
        }

        // Extract query parameters used by list operations
        if (route == ConversationRoute.ITEMS_LIST) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val route = detectRoute(response.url, response.requestMethod)

        // Override operation name set by common response handling so it reflects the route
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE, ConversationRoute.UPDATE -> {
                // Conversation object response: extract id and creation timestamp
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.DELETE -> {
                // Deletion response: { id, deleted, object }
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["deleted"]?.let {
                    span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean)
                }
            }

            ConversationRoute.ITEMS_CREATE, ConversationRoute.ITEMS_RETRIEVE -> {
                // Single conversation item response
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }

            ConversationRoute.ITEMS_LIST -> {
                // Paginated list response: { data: ConversationItem[], first_id, last_id, has_more }
                val data = body["data"]
                span.setAttribute(
                    "tracy.conversation.items.count",
                    if (data is JsonArray) data.size.toLong() else 0L
                )
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

            ConversationRoute.ITEMS_DELETE -> {
                // Item deletion response: { id, deleted, object }
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["deleted"]?.let {
                    span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversations endpoint is being called based on the URL path and
     * HTTP method.
     *
     * Route priority:
     * 1. Item-level routes (path contains "items")
     * 2. Conversation-level routes (only "conversations/{id}" or "conversations")
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversation route. Endpoint has no `conversations` path segment: " +
                        segments.joinToString(separator = "/")
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
            // Item-level routes
            hasConversationId && hasItemsSegment && hasItemId && method == "GET" -> ConversationRoute.ITEMS_RETRIEVE
            hasConversationId && hasItemsSegment && hasItemId && method == "DELETE" -> ConversationRoute.ITEMS_DELETE
            hasConversationId && hasItemsSegment && !hasItemId && method == "POST" -> ConversationRoute.ITEMS_CREATE
            hasConversationId && hasItemsSegment && !hasItemId && method == "GET" -> ConversationRoute.ITEMS_LIST

            // Conversation-level routes
            !hasConversationId && method == "POST" -> ConversationRoute.CREATE
            hasConversationId && !hasItemsSegment && method == "GET" -> ConversationRoute.RETRIEVE
            hasConversationId && !hasItemsSegment && (method == "POST" || method == "PATCH") -> ConversationRoute.UPDATE
            hasConversationId && !hasItemsSegment && method == "DELETE" -> ConversationRoute.DELETE

            else -> {
                logger.warn {
                    "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}"
                }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Extracts the conversation ID from a path like `/v1/conversations/{id}` or
     * `/v1/conversations/{id}/items`.
     */
    private fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val potentialId = segments[conversationsIndex + 1]
        return if (potentialId.isNotBlank()) potentialId else null
    }

    /**
     * Extracts the item ID from a path like `/v1/conversations/{id}/items/{item_id}`.
     */
    private fun extractItemIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
        val potentialId = segments[itemsIndex + 1]
        return if (potentialId.isNotBlank()) potentialId else null
    }

    /**
     * All supported routes for the Conversations API together with the corresponding
     * [gen_ai.operation.name][GEN_AI_OPERATION_NAME] value.
     */
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
