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
 * Covers all eight conversation routes:
 * - `POST /v1/conversations` → `conversations.create`
 * - `GET /v1/conversations/{id}` → `conversations.retrieve`
 * - `PATCH /v1/conversations/{id}` → `conversations.update`
 * - `DELETE /v1/conversations/{id}` → `conversations.delete`
 * - `POST /v1/conversations/{id}/items` → `conversations.items.create`
 * - `GET /v1/conversations/{id}/items` → `conversations.items.list`
 * - `GET /v1/conversations/{id}/items/{item_id}` → `conversations.items.retrieve`
 * - `DELETE /v1/conversations/{id}/items/{item_id}` → `conversations.items.delete`
 *
 * For every route the handler sets:
 * - `gen_ai.operation.name` derived from the URL path and HTTP method (overrides the value set by
 *   [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes])
 * - `openai.api.type = "conversations"`
 *
 * See [OpenAI Conversations API](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /** Distinguishes between the eight Conversations API routes. */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        RETRIEVE("conversations.retrieve"),
        UPDATE("conversations.update"),
        DELETE("conversations.delete"),
        ITEMS_CREATE("conversations.items.create"),
        ITEMS_LIST("conversations.items.list"),
        ITEM_RETRIEVE("conversations.items.retrieve"),
        ITEM_DELETE("conversations.items.delete"),
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
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

        // Override gen_ai.operation.name that setCommonResponseAttributes may have set via body["object"]
        span.setAttribute("gen_ai.operation.name", route.operationName)
        span.setAttribute("openai.api.type", "conversations")

        val segments = response.url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        when (route) {
            ConversationRoute.CREATE,
            ConversationRoute.RETRIEVE,
            ConversationRoute.UPDATE,
            ConversationRoute.DELETE -> {
                val body = response.body.asJson()?.jsonObject ?: return
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
                body["deleted"]?.let { span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
            }

            ConversationRoute.ITEMS_CREATE,
            ConversationRoute.ITEMS_LIST -> {
                extractConversationIdFromPath(segments, conversationsIndex)
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                val body = response.body.asJson()?.jsonObject ?: return
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                }
                body["first_id"]?.let { span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
                body["last_id"]?.let { span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
                body["has_more"]?.let { span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean) }
            }

            ConversationRoute.ITEM_RETRIEVE,
            ConversationRoute.ITEM_DELETE -> {
                extractConversationIdFromPath(segments, conversationsIndex)
                    ?.let { span.setAttribute("gen_ai.conversation.id", it) }
                val body = response.body.asJson()?.jsonObject ?: return
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }
        }
    }

    /** Conversations API does not use SSE streaming. */
    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Infers the [ConversationRoute] from the URL path segments and HTTP method.
     *
     * Path layout (relative to the "conversations" segment):
     * - no suffix → conversation-level (CREATE/RETRIEVE/UPDATE/DELETE)
     * - `/{id}/items` → items collection (ITEMS_CREATE/ITEMS_LIST)
     * - `/{id}/items/{item_id}` → single item (ITEM_RETRIEVE/ITEM_DELETE)
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")

        if (conversationsIndex == -1) {
            logger.warn { "No 'conversations' segment in path: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val after = segments.drop(conversationsIndex + 1)
        val hasConversationId = after.isNotEmpty() && after[0].isNotBlank()
        val itemsRelIdx = after.indexOf("items")
        val hasItemsSegment = itemsRelIdx != -1
        val hasItemId = hasItemsSegment &&
                itemsRelIdx + 1 < after.size &&
                after[itemsRelIdx + 1].isNotBlank()

        return when {
            method == "POST"   && !hasConversationId                          -> ConversationRoute.CREATE
            method == "GET"    && hasConversationId && !hasItemsSegment       -> ConversationRoute.RETRIEVE
            method == "PATCH"  && hasConversationId && !hasItemsSegment       -> ConversationRoute.UPDATE
            method == "DELETE" && hasConversationId && !hasItemsSegment       -> ConversationRoute.DELETE
            method == "POST"   && hasConversationId && hasItemsSegment        -> ConversationRoute.ITEMS_CREATE
            method == "GET"    && hasConversationId && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET"    && hasConversationId && hasItemsSegment && hasItemId  -> ConversationRoute.ITEM_RETRIEVE
            method == "DELETE" && hasConversationId && hasItemsSegment && hasItemId  -> ConversationRoute.ITEM_DELETE
            else -> {
                logger.warn { "Unrecognised conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /** Returns the conversation ID segment that follows the "conversations" path segment. */
    private fun extractConversationIdFromPath(segments: List<String>, conversationsIndex: Int): String? {
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val id = segments[conversationsIndex + 1]
        return id.takeIf { it.isNotBlank() && it != "conversations" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
