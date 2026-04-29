/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
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
 * The Conversations API provides endpoints for managing multi-turn conversation sessions
 * and their items (messages, function calls, etc.):
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Retrieve a conversation
 * 3. `DELETE /conversations/{conversation_id}` - Delete a conversation
 * 4. `POST /conversations/{conversation_id}/items` - Create a conversation item
 * 5. `GET /conversations/{conversation_id}/items` - List conversation items
 * 6. `GET /conversations/{conversation_id}/items/{item_id}` - Retrieve a conversation item
 * 7. `DELETE /conversations/{conversation_id}/items/{item_id}` - Delete a conversation item
 *
 * This handler detects the specific route and traces accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute("gen_ai.operation.name", route.operationName)

        // Extract conversation ID for all routes that include one in the path
        if (route != ConversationRoute.CREATE) {
            extractConversationId(request.url)?.let {
                span.setAttribute("gen_ai.conversation.id", it)
            }
        }

        // List items: read pagination query parameters
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

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE -> {
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.DELETE -> {
                span.setAttribute("tracy.conversation.deleted", true)
            }

            ConversationRoute.ITEMS_CREATE -> {
                // No specific response attributes required for item creation
            }

            ConversationRoute.ITEMS_LIST -> {
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                }
                body["first_id"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.items.first_id", it.content)
                }
                body["last_id"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.items.last_id", it.content)
                }
                body["has_more"]?.let {
                    span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
                }
            }

            ConversationRoute.ITEMS_RETRIEVE -> {
                body["id"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.item.id", it.content)
                }
                body["type"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.item.type", it.content)
                }
                body["status"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.item.status", it.content)
                }
            }

            ConversationRoute.ITEMS_DELETE -> {
                body["id"]?.jsonPrimitive?.let {
                    span.setAttribute("tracy.conversation.item.id", it.content)
                }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific Conversations endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()
        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1
        val hasItemId = hasItems && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

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
     * Extracts the conversation ID from a path like `/v1/conversations/{conversation_id}[/...]`.
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
            val id = segments[conversationsIndex + 1]
            if (id.isNotBlank()) id else null
        } else {
            null
        }
    }

    /**
     * Internal enum mapping each Conversations API route to its [operationName] attribute value.
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
