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
 * Handler for the OpenAI Conversations API.
 *
 * Handles the following routes:
 * 1. `POST /conversations` — Create a new conversation
 * 2. `GET /conversations/{conversation_id}` — Retrieve a conversation
 * 3. `POST /conversations/{conversation_id}` — Update a conversation
 * 4. `DELETE /conversations/{conversation_id}` — Delete a conversation
 * 5. `POST /conversations/{conversation_id}/items` — Add items to a conversation
 * 6. `GET /conversations/{conversation_id}/items` — List items in a conversation
 * 7. `GET /conversations/{conversation_id}/items/{item_id}` — Retrieve a specific item
 * 8. `DELETE /conversations/{conversation_id}/items/{item_id}` — Delete a specific item
 *
 * See: [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    private enum class ConversationRoute {
        CREATE,          // POST /conversations
        RETRIEVE,        // GET /conversations/{id}
        UPDATE,          // POST /conversations/{id}
        DELETE,          // DELETE /conversations/{id}
        ITEMS_CREATE,    // POST /conversations/{id}/items
        ITEMS_LIST,      // GET /conversations/{id}/items
        ITEMS_RETRIEVE,  // GET /conversations/{id}/items/{item_id}
        ITEMS_DELETE     // DELETE /conversations/{id}/items/{item_id}
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")

        val convId = extractConversationId(request.url)
        if (convId != null) {
            span.setAttribute("gen_ai.conversation.id", convId)
        }

        val route = detectRoute(request.url, request.method)
        if (route == ConversationRoute.ITEMS_LIST) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        val body = response.body.asJson()?.jsonObject

        span.setAttribute("openai.api.type", "conversations")

        // For CREATE the conversation ID isn't in the URL path; use the response body's "id" as fallback.
        val convId = extractConversationId(response.url)
            ?: body?.get("id")?.jsonPrimitive?.content
        if (convId != null) {
            span.setAttribute("gen_ai.conversation.id", convId)
        }

        when (route) {
            ConversationRoute.CREATE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.create")
                body?.get("created_at")?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.RETRIEVE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.retrieve")
                body?.get("created_at")?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.UPDATE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.update")
                body?.get("created_at")?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.DELETE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.delete")
                span.setAttribute("tracy.conversation.deleted", true)
            }

            ConversationRoute.ITEMS_CREATE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.items.create")
                setItemsListAttributes(span, body)
            }

            ConversationRoute.ITEMS_LIST -> {
                span.setAttribute("gen_ai.operation.name", "conversations.items.list")
                setItemsListAttributes(span, body)
            }

            ConversationRoute.ITEMS_RETRIEVE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.items.retrieve")
                body?.get("id")?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.conversation.item.id", it)
                }
                body?.get("type")?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.conversation.item.type", it)
                }
                body?.get("status")?.jsonPrimitive?.content?.let {
                    span.setAttribute("tracy.conversation.item.status", it)
                }
            }

            ConversationRoute.ITEMS_DELETE -> {
                span.setAttribute("gen_ai.operation.name", "conversations.items.delete")
                val itemId = extractItemId(response.url)
                if (itemId != null) {
                    span.setAttribute("tracy.conversation.item.id", itemId)
                }
                // The response for item delete is the updated Conversation object.
                body?.get("created_at")?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which Conversations API route is being called based on URL path segments and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convIndex = segments.indexOf("conversations")
        if (convIndex == -1) {
            logger.warn { "No 'conversations' segment in path: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConvId = segments.size > convIndex + 1 && segments[convIndex + 1].isNotBlank()
        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && itemsIndex > convIndex
        val hasItemId = hasItems && segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            !hasConvId && method == "POST" -> ConversationRoute.CREATE
            hasConvId && !hasItems && method == "GET" -> ConversationRoute.RETRIEVE
            hasConvId && !hasItems && method == "POST" -> ConversationRoute.UPDATE
            hasConvId && !hasItems && method == "DELETE" -> ConversationRoute.DELETE
            hasConvId && hasItems && !hasItemId && method == "POST" -> ConversationRoute.ITEMS_CREATE
            hasConvId && hasItems && !hasItemId && method == "GET" -> ConversationRoute.ITEMS_LIST
            hasConvId && hasItems && hasItemId && method == "GET" -> ConversationRoute.ITEMS_RETRIEVE
            hasConvId && hasItems && hasItemId && method == "DELETE" -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Extracts the conversation ID from the path segment immediately after "conversations".
     * Returns null if no such segment exists (e.g., for the CREATE endpoint).
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val convIndex = segments.indexOf("conversations")
        if (convIndex == -1 || segments.size <= convIndex + 1) return null
        val id = segments[convIndex + 1]
        return if (id.isNotBlank()) id else null
    }

    /**
     * Extracts the item ID from the path segment immediately after "items".
     */
    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
        val id = segments[itemsIndex + 1]
        return if (id.isNotBlank()) id else null
    }

    /**
     * Sets common item-list response attributes: count, first_id, last_id, has_more.
     * Used for both ITEMS_CREATE and ITEMS_LIST responses.
     */
    private fun setItemsListAttributes(span: Span, body: kotlinx.serialization.json.JsonObject?) {
        val data = body?.get("data")
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }
        body?.get("first_id")?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.first_id", it)
        }
        body?.get("last_id")?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.conversation.items.last_id", it)
        }
        body?.get("has_more")?.jsonPrimitive?.boolean?.let {
            span.setAttribute("tracy.conversation.items.has_more", it)
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
