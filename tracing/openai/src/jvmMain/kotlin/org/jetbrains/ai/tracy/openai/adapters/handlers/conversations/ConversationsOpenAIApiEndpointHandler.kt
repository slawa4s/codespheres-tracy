/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
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
 * Handler for the OpenAI Conversations API (`/v1/conversations`).
 *
 * Detects all eight conversation sub-routes and sets the correct span attributes for each:
 * 1. `POST /conversations`                               → CONVERSATION_CREATE
 * 2. `GET  /conversations/{conversation_id}`             → CONVERSATION_RETRIEVE
 * 3. `POST /conversations/{conversation_id}`             → CONVERSATION_UPDATE
 * 4. `DELETE /conversations/{conversation_id}`           → CONVERSATION_DELETE
 * 5. `POST /conversations/{conversation_id}/items`       → ITEMS_CREATE
 * 6. `GET  /conversations/{conversation_id}/items`       → ITEMS_LIST
 * 7. `GET  /conversations/{conversation_id}/items/{id}`  → ITEM_RETRIEVE
 * 8. `DELETE /conversations/{conversation_id}/items/{id}`→ ITEM_DELETE
 *
 * The handler always overrides `gen_ai.operation.name` to the route-derived value
 * (e.g. "conversations.create") and always sets `openai.api.type` = "conversations".
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        when (route) {
            ConversationRoute.ITEMS_LIST -> {
                val params = request.url.parameters
                params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
                params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
                params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
            }
            ConversationRoute.CONVERSATION_CREATE,
            ConversationRoute.ITEMS_CREATE -> {
                val body = request.body.asJson()?.jsonObject ?: return
                val items = body["items"]
                if (items is JsonArray) {
                    span.setAttribute("tracy.request.items.count", items.size.toLong())
                }
            }
            else -> { /* no specific request attributes for other routes */ }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)

        // Always override gen_ai.operation.name to the route-derived value
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        // Always set openai.api.type
        span.setAttribute("openai.api.type", "conversations")

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            ConversationRoute.CONVERSATION_CREATE,
            ConversationRoute.CONVERSATION_RETRIEVE,
            ConversationRoute.CONVERSATION_UPDATE -> {
                body["id"]?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("tracy.conversation.created_at", it)
                }
            }

            ConversationRoute.CONVERSATION_DELETE -> {
                body["id"]?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it.jsonPrimitive.content) }
                body["deleted"]?.let {
                    span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean)
                }
            }

            ConversationRoute.ITEMS_LIST,
            ConversationRoute.ITEMS_CREATE -> {
                extractConversationIdFromPath(response.url)
                    ?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
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

            ConversationRoute.ITEM_RETRIEVE -> {
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }

            ConversationRoute.ITEM_DELETE -> {
                val segments = response.url.pathSegments
                val itemsIndex = segments.indexOf("items")
                if (itemsIndex != -1 && segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()) {
                    span.setAttribute("tracy.conversation.item.id", segments[itemsIndex + 1])
                }
                extractConversationIdFromPath(response.url)
                    ?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
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
     * Internal enum distinguishing all eight conversation API routes.
     */
    internal enum class ConversationRoute(val operationName: String) {
        CONVERSATION_CREATE("conversations.create"),
        CONVERSATION_RETRIEVE("conversations.retrieve"),
        CONVERSATION_UPDATE("conversations.update"),
        CONVERSATION_DELETE("conversations.delete"),
        ITEMS_CREATE("conversations.items.create"),
        ITEMS_LIST("conversations.items.list"),
        ITEM_RETRIEVE("conversations.items.retrieve"),
        ITEM_DELETE("conversations.items.delete")
    }

    /**
     * Detects the conversation route from the URL path segments and HTTP method.
     */
    internal fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convsIndex = segments.indexOf("conversations")
        if (convsIndex == -1) {
            logger.warn {
                "Failed to detect conversation route: no 'conversations' segment in " +
                        segments.joinToString("/")
            }
            return ConversationRoute.CONVERSATION_CREATE
        }

        val hasConvId = segments.size > convsIndex + 1 && segments[convsIndex + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val itemsIndex = if (hasItems) segments.indexOf("items") else -1
        val hasItemId = hasItems && segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConvId -> ConversationRoute.CONVERSATION_CREATE
            method == "GET" && hasConvId && !hasItems -> ConversationRoute.CONVERSATION_RETRIEVE
            method == "POST" && hasConvId && !hasItems -> ConversationRoute.CONVERSATION_UPDATE
            method == "DELETE" && hasConvId && !hasItems -> ConversationRoute.CONVERSATION_DELETE
            method == "POST" && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItems && hasItemId -> ConversationRoute.ITEM_RETRIEVE
            method == "DELETE" && hasItems && hasItemId -> ConversationRoute.ITEM_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CONVERSATION_CREATE
            }
        }
    }

    private fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val convsIndex = segments.indexOf("conversations")
        return if (convsIndex != -1 && segments.size > convsIndex + 1) {
            val potentialId = segments[convsIndex + 1]
            if (potentialId.isNotBlank()) potentialId else null
        } else {
            null
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
