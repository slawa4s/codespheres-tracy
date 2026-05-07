/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
 * The Conversations API provides endpoints for managing multi-turn conversations:
 * 1. `POST /conversations` — Create a new conversation (CREATE)
 * 2. `GET /conversations/{conversation_id}` — Retrieve a conversation (RETRIEVE)
 * 3. `POST /conversations/{conversation_id}` — Update a conversation (UPDATE)
 * 4. `DELETE /conversations/{conversation_id}` — Delete a conversation (DELETE)
 * 5. `POST /conversations/{conversation_id}/items` — Add items to a conversation (ITEMS_CREATE)
 * 6. `GET /conversations/{conversation_id}/items` — List items in a conversation (ITEMS_LIST)
 * 7. `GET /conversations/{conversation_id}/items/{item_id}` — Retrieve a single item (ITEMS_RETRIEVE)
 * 8. `DELETE /conversations/{conversation_id}/items/{item_id}` — Delete an item (ITEMS_DELETE)
 *
 * This handler detects the specific route and emits the correct `gen_ai.operation.name` and
 * conversation/item attributes.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * The eight sub-routes of the Conversations API.
     * Each route maps to a dot-notation operation name used for [GEN_AI_OPERATION_NAME].
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

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)

        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val conversationId = extractConversationId(request.url)
        conversationId?.let { span.setAttribute("gen_ai.conversation.id", it) }

        when (route) {
            ConversationRoute.ITEMS_RETRIEVE, ConversationRoute.ITEMS_DELETE -> {
                extractItemId(request.url)?.let { span.setAttribute("tracy.conversation.item.id", it) }
            }
            ConversationRoute.ITEMS_LIST -> {
                request.url.parameters.queryParameter("limit")
                    ?.let { span.setAttribute("tracy.request.limit", it) }
                request.url.parameters.queryParameter("order")
                    ?.let { span.setAttribute("tracy.request.order", it) }
                request.url.parameters.queryParameter("after")
                    ?.let { span.setAttribute("tracy.request.after", it) }
            }
            else -> Unit
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)

        // Override GEN_AI_OPERATION_NAME with the route-specific name.
        // setCommonResponseAttributes (called by the adapter before this method) sets it
        // from the `object` field in the response body (e.g. "conversation", "list"),
        // which does not match the expected dot-notation names.
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE, ConversationRoute.UPDATE -> {
                body["id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                body["created_at"]?.jsonPrimitive?.longOrNull
                    ?.let { span.setAttribute("tracy.conversation.created_at", it) }
            }

            ConversationRoute.DELETE -> {
                body["id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                body["deleted"]?.jsonPrimitive?.booleanOrNull
                    ?.let { span.setAttribute("tracy.conversation.deleted", it) }
            }

            ConversationRoute.ITEMS_CREATE, ConversationRoute.ITEMS_LIST -> {
                extractConversationId(response.url)
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                }
                body["first_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("tracy.conversation.items.first_id", it) }
                body["last_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("tracy.conversation.items.last_id", it) }
                body["has_more"]?.jsonPrimitive?.booleanOrNull
                    ?.let { span.setAttribute("tracy.conversation.items.has_more", it) }
            }

            ConversationRoute.ITEMS_RETRIEVE -> {
                extractConversationId(response.url)
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                body["id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("tracy.conversation.item.id", it) }
                body["type"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("tracy.conversation.item.type", it) }
                body["status"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("tracy.conversation.item.status", it) }
            }

            ConversationRoute.ITEMS_DELETE -> {
                // The response body for item deletion is the parent conversation object.
                body["id"]?.jsonPrimitive?.contentOrNull
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                body["created_at"]?.jsonPrimitive?.longOrNull
                    ?.let { span.setAttribute("tracy.conversation.created_at", it) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which Conversations sub-route is being called.
     *
     * Detection logic:
     * - Locates the `conversations` segment in the URL path.
     * - Determines whether a conversation-id, `items` segment, or item-id is present.
     * - Branches on HTTP method + those flags to select the matching [ConversationRoute].
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

        val nextAfterConversations = segments.getOrNull(conversationsIndex + 1)
        val hasConversationId = nextAfterConversations != null &&
            nextAfterConversations.isNotBlank() &&
            nextAfterConversations != "conversations"

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && hasConversationId

        val nextAfterItems = if (hasItems) segments.getOrNull(itemsIndex + 1) else null
        val hasItemId = nextAfterItems != null && nextAfterItems.isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItems -> ConversationRoute.RETRIEVE
            (method == "POST" || method == "PATCH") && hasConversationId && !hasItems -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItems -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasConversationId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasConversationId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn {
                    "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}"
                }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Extracts the conversation ID from a path like:
     * - `/v1/conversations/{conversation_id}`
     * - `/v1/conversations/{conversation_id}/items`
     * - `/v1/conversations/{conversation_id}/items/{item_id}`
     */
    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) return null
        val candidate = segments.getOrNull(conversationsIndex + 1) ?: return null
        return candidate.takeIf { it.isNotBlank() && it != "conversations" && it != "items" }
    }

    /**
     * Extracts the item ID from a path like:
     * `/v1/conversations/{conversation_id}/items/{item_id}`
     */
    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1) return null
        val candidate = segments.getOrNull(itemsIndex + 1) ?: return null
        return candidate.takeIf { it.isNotBlank() }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
