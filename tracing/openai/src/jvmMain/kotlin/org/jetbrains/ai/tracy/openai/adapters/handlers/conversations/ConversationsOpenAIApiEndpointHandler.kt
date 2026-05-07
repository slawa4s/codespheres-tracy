/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing persistent conversations:
 * 1. `POST /v1/conversations` — Create a new conversation
 * 2. `GET /v1/conversations/{conversation_id}` — Retrieve a conversation
 * 3. `POST /v1/conversations/{conversation_id}` — Update a conversation
 * 4. `DELETE /v1/conversations/{conversation_id}` — Delete a conversation
 * 5. `POST /v1/conversations/{conversation_id}/items` — Add items to a conversation
 * 6. `GET /v1/conversations/{conversation_id}/items` — List items in a conversation
 * 7. `GET /v1/conversations/{conversation_id}/items/{item_id}` — Retrieve a specific item
 * 8. `DELETE /v1/conversations/{conversation_id}/items/{item_id}` — Delete a specific item
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        val conversationId = extractConversationId(request.url)
        val itemId = extractItemId(request.url)

        conversationId?.let { span.setAttribute("gen_ai.request.conversation.id", it) }
        itemId?.let { span.setAttribute("gen_ai.request.item.id", it) }
        span.setAttribute("gen_ai.operation.name", route.operationName)

        val body = request.body.asJson()?.jsonObject ?: return
        when (route) {
            ConversationRoute.CREATE -> {
                body["metadata"]?.let { span.setAttribute("gen_ai.request.metadata", it.toString()) }
            }
            ConversationRoute.UPDATE -> {
                body["metadata"]?.let { span.setAttribute("gen_ai.request.metadata", it.toString()) }
            }
            ConversationRoute.ITEM_CREATE -> {
                body["items"]?.let { span.setAttribute("gen_ai.request.items", it.toString()) }
            }
            ConversationRoute.ITEM_LIST -> {
                body["limit"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.request.limit", it) }
                body["after"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.request.after", it) }
                body["before"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("gen_ai.request.before", it) }
            }
            else -> { /* no additional request attributes */ }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        val body = response.body.asJson()?.jsonObject ?: return

        // Re-assert the operation name to override the value set by the common response attribute handler,
        // which reads from body["object"] (e.g., "realtime.conversation") and is less informative.
        span.setAttribute("gen_ai.operation.name", route.operationName)

        when (route) {
            ConversationRoute.CREATE,
            ConversationRoute.RETRIEVE,
            ConversationRoute.UPDATE -> traceConversation(span, body)

            ConversationRoute.DELETE -> {
                body["id"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("gen_ai.response.conversation.id", it)
                }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.deleted", it)
                }
            }

            ConversationRoute.ITEM_CREATE,
            ConversationRoute.ITEM_RETRIEVE -> traceConversationItemList(span, body)

            ConversationRoute.ITEM_LIST -> {
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("gen_ai.response.items_count", data.size.toLong())
                    for ((index, item) in data.withIndex()) {
                        traceConversationItemInto(span, item.jsonObject, "gen_ai.response.items.$index")
                    }
                }
                body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.has_more", it)
                }
            }

            ConversationRoute.ITEM_DELETE -> {
                body["id"]?.jsonPrimitive?.contentOrNull?.let {
                    span.setAttribute("gen_ai.response.conversation.id", it)
                }
                body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                    span.setAttribute("gen_ai.response.deleted", it)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    // ── Route detection ───────────────────────────────────────────────────────

    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Could not detect conversation route; no 'conversations' segment in: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val hasItemsSegment = segments.contains("items")

        val hasItemId = if (hasItemsSegment) {
            val itemsIndex = segments.indexOf("items")
            segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()
        } else {
            false
        }

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && hasConversationId && !hasItemsSegment -> ConversationRoute.RETRIEVE
            method == "POST" && hasConversationId && !hasItemsSegment -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItemsSegment -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasItemsSegment && !hasItemId -> ConversationRoute.ITEM_CREATE
            method == "GET" && hasConversationId && hasItemsSegment && !hasItemId -> ConversationRoute.ITEM_LIST
            method == "GET" && hasConversationId && hasItemsSegment && hasItemId -> ConversationRoute.ITEM_RETRIEVE
            method == "DELETE" && hasConversationId && hasItemsSegment && hasItemId -> ConversationRoute.ITEM_DELETE
            else -> {
                logger.warn { "Unknown conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    // ── ID helpers ────────────────────────────────────────────────────────────

    private fun extractConversationId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val idx = segments.indexOf("conversations")
        if (idx == -1 || segments.size <= idx + 1) return null
        val candidate = segments[idx + 1]
        return candidate.takeIf { it.isNotBlank() && it != "items" }
    }

    private fun extractItemId(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val itemsIdx = segments.indexOf("items")
        if (itemsIdx == -1 || segments.size <= itemsIdx + 1) return null
        val candidate = segments[itemsIdx + 1]
        return candidate.takeIf { it.isNotBlank() }
    }

    // ── Tracing helpers ───────────────────────────────────────────────────────

    /**
     * Traces the fields of a [Conversation](https://platform.openai.com/docs/api-reference/conversations/object) object.
     */
    private fun traceConversation(span: Span, body: JsonObject) {
        body["id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.id", it)
        }
        body["object"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.object", it)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.created_at", it)
        }
        body["metadata"]?.let {
            if (it !is JsonNull) {
                span.setAttribute("gen_ai.response.conversation.metadata", it.toString())
            }
        }
    }

    /**
     * Traces a `ConversationItemList` response (returned by item create and item retrieve).
     */
    private fun traceConversationItemList(span: Span, body: JsonObject) {
        body["object"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.response.items.object", it)
        }
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.items_count", data.size.toLong())
            for ((index, item) in data.withIndex()) {
                traceConversationItemInto(span, item.jsonObject, "gen_ai.response.items.$index")
            }
        }
    }

    private fun traceConversationItemInto(span: Span, item: JsonObject, prefix: String) {
        item["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("$prefix.id", it) }
        item["type"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("$prefix.type", it) }
        item["object"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("$prefix.object", it) }
        item["role"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("$prefix.role", it) }
        item["status"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("$prefix.status", it) }
    }

    /**
     * Internal enum representing the different routes of the Conversations API.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("create_conversation"),
        RETRIEVE("retrieve_conversation"),
        UPDATE("update_conversation"),
        DELETE("delete_conversation"),
        ITEM_CREATE("create_conversation_items"),
        ITEM_RETRIEVE("retrieve_conversation_item"),
        ITEM_LIST("list_conversation_items"),
        ITEM_DELETE("delete_conversation_item"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
