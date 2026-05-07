/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * The Conversations API provides endpoints to create and manage persistent conversations
 * and their items (messages, tool calls, etc.):
 * 1. `POST /conversations` - Create a conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` - Update a conversation
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 5. `POST /conversations/{conversation_id}/items` - Create items in a conversation
 * 6. `GET /conversations/{conversation_id}/items` - List items in a conversation
 * 7. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a specific item
 * 8. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a specific item
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")

        val route = detectRoute(request.url, request.method)
        val segments = request.url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        // Extract conversation ID from URL path for non-CREATE routes
        if (route != ConversationRoute.CREATE && conversationsIndex != -1 &&
            segments.size > conversationsIndex + 1
        ) {
            val convId = segments[conversationsIndex + 1]
            if (convId.isNotBlank()) {
                span.setAttribute("gen_ai.conversation.id", convId)
            }
        }

        // For ITEMS_LIST, extract pagination query parameters
        if (route == ConversationRoute.ITEMS_LIST) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        val body = response.body.asJson()?.jsonObject ?: return

        // Override the operation name set by setCommonResponseAttributes
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE, ConversationRoute.UPDATE -> {
                // Response: Conversation { id, created_at, object, metadata }
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.DELETE -> {
                // Response: ConversationDeletedResource { id, deleted, object }
                body["deleted"]?.let {
                    span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean)
                }
            }

            ConversationRoute.ITEMS_CREATE, ConversationRoute.ITEMS_LIST -> {
                // Response: ConversationItemList { data, first_id, last_id, has_more, object }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                } else {
                    span.setAttribute("tracy.conversation.items.count", 0L)
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
                // Response: ConversationItem (union type with id, type, status fields)
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                // status may not be present for all item types
                body["status"]?.let {
                    span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content)
                }
            }

            ConversationRoute.ITEMS_DELETE -> {
                // Response: Conversation object (the parent conversation after item removal)
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
                // Extract item ID from URL path
                val segments = response.url.pathSegments
                val itemsIndex = segments.indexOf("items")
                if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
                    val itemId = segments[itemsIndex + 1]
                    if (itemId.isNotBlank()) {
                        span.setAttribute("tracy.conversation.item.id", itemId)
                    }
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     *
     * URL patterns:
     * - `POST /conversations`                             → CREATE
     * - `GET /conversations/{id}`                        → RETRIEVE
     * - `POST /conversations/{id}`                       → UPDATE
     * - `DELETE /conversations/{id}`                     → DELETE
     * - `POST /conversations/{id}/items`                 → ITEMS_CREATE
     * - `GET /conversations/{id}/items`                  → ITEMS_LIST
     * - `GET /conversations/{id}/items/{item_id}`        → ITEMS_RETRIEVE
     * - `DELETE /conversations/{id}/items/{item_id}`     → ITEMS_DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${url.pathSegments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasConvId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1
        val hasItemId = hasItems && segments.size > (itemsIndex + 1) &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConvId -> ConversationRoute.CREATE
            method == "GET" && hasConvId && !hasItems -> ConversationRoute.RETRIEVE
            method == "POST" && hasConvId && !hasItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConvId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasConvId && hasItems && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasConvId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasConvId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasConvId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different Conversations API routes.
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
