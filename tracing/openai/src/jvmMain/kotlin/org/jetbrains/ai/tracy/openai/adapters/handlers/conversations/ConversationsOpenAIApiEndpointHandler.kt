/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.ConversationRouteHandler
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractConversationIdFromPath
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractItemIdFromPath

/**
 * Handler for the OpenAI Conversations API.
 *
 * Detects which specific Conversations endpoint is being called from the URL path and HTTP method,
 * then delegates attribute extraction to the appropriate [ConversationRouteHandler].
 *
 * Supported routes:
 * 1. `POST /v1/conversations` — create a conversation
 * 2. `GET /v1/conversations/{conversation_id}` — retrieve a conversation
 * 3. `PATCH /v1/conversations/{conversation_id}` — update a conversation
 * 4. `DELETE /v1/conversations/{conversation_id}` — delete a conversation
 * 5. `POST /v1/conversations/{conversation_id}/items` — create a conversation item
 * 6. `GET /v1/conversations/{conversation_id}/items` — list conversation items
 * 7. `GET /v1/conversations/{conversation_id}/items/{item_id}` — retrieve a conversation item
 * 8. `DELETE /v1/conversations/{conversation_id}/items/{item_id}` — delete a conversation item
 *
 * Common attributes (`gen_ai.response.id`, `gen_ai.operation.name`, `gen_ai.response.model`) are
 * set upstream by [org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter]; handlers here
 * record only route-specific attributes.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /** Routes within the Conversations API, distinguished by URL path shape and HTTP method. */
    private enum class ConversationRoute {
        CREATE,         // POST  /conversations
        RETRIEVE,       // GET   /conversations/{conversation_id}
        UPDATE,         // PATCH /conversations/{conversation_id}
        DELETE,         // DELETE /conversations/{conversation_id}
        CREATE_ITEM,    // POST  /conversations/{conversation_id}/items
        LIST_ITEMS,     // GET   /conversations/{conversation_id}/items
        RETRIEVE_ITEM,  // GET   /conversations/{conversation_id}/items/{item_id}
        DELETE_ITEM     // DELETE /conversations/{conversation_id}/items/{item_id}
    }

    /**
     * Route handler registry, initialised lazily to avoid allocating handlers until first use.
     */
    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.UPDATE to UpdateConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.CREATE_ITEM to CreateItemHandler(),
            ConversationRoute.LIST_ITEMS to ListItemsHandler(),
            ConversationRoute.RETRIEVE_ITEM to RetrieveItemHandler(),
            ConversationRoute.DELETE_ITEM to DeleteItemHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Determines the [ConversationRoute] from [url] path segments and HTTP [method].
     *
     * Decision tree:
     * - If `"items"` segment is present after `"conversations"`:
     *   - segment after `"items"` present → item-level operation (RETRIEVE_ITEM / DELETE_ITEM)
     *   - no segment after `"items"` → collection-level item operation (LIST_ITEMS / CREATE_ITEM)
     * - Otherwise:
     *   - segment after `"conversations"` present → conversation-level operation (RETRIEVE / UPDATE / DELETE)
     *   - no segment after `"conversations"` → CREATE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn {
                "Failed to detect conversation route — no 'conversations' path segment: " +
                        segments.joinToString("/")
            }
            return ConversationRoute.CREATE
        }

        val hasConversationId = segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        val itemsIndex = segments.indexOf("items")
        val hasItems = itemsIndex != -1 && itemsIndex > conversationsIndex
        val hasItemId = hasItems && segments.size > itemsIndex + 1 &&
                segments[itemsIndex + 1].isNotBlank()

        return when {
            hasItems && hasItemId && method == "GET" -> ConversationRoute.RETRIEVE_ITEM
            hasItems && hasItemId && method == "DELETE" -> ConversationRoute.DELETE_ITEM
            hasItems && !hasItemId && method == "GET" -> ConversationRoute.LIST_ITEMS
            hasItems && !hasItemId && method == "POST" -> ConversationRoute.CREATE_ITEM
            !hasConversationId && method == "POST" -> ConversationRoute.CREATE
            hasConversationId && !hasItems && method == "GET" -> ConversationRoute.RETRIEVE
            hasConversationId && !hasItems && method == "PATCH" -> ConversationRoute.UPDATE
            hasConversationId && !hasItems && method == "DELETE" -> ConversationRoute.DELETE
            else -> {
                logger.warn {
                    "Failed to detect conversation route: $method ${segments.joinToString("/")}"
                }
                ConversationRoute.CREATE
            }
        }
    }

    // ── Route handler implementations ──────────────────────────────────────────────────────────

    /** Handles `POST /conversations` — creates a new conversation. */
    private class CreateConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            val body = request.body.asJson()?.jsonObject ?: return
            body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.id", it.jsonPrimitive.content) }
        }
    }

    /** Handles `GET /conversations/{conversation_id}` — retrieves an existing conversation. */
    private class RetrieveConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.id", it.jsonPrimitive.content) }
        }
    }

    /** Handles `PATCH /conversations/{conversation_id}` — updates an existing conversation. */
    private class UpdateConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.id", it.jsonPrimitive.content) }
        }
    }

    /** Handles `DELETE /conversations/{conversation_id}` — deletes a conversation. */
    private class DeleteConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
        }

        /** Response: `{ id, deleted, object }` */
        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.id", it.jsonPrimitive.content) }
            body["deleted"]?.let { span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean) }
        }
    }

    /** Handles `POST /conversations/{conversation_id}/items` — adds an item to a conversation. */
    private class CreateItemHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
            val body = request.body.asJson()?.jsonObject ?: return
            body["type"]?.let { span.setAttribute("openai.conversation.item.type", it.jsonPrimitive.content) }
            body["role"]?.let { span.setAttribute("openai.conversation.item.role", it.jsonPrimitive.content) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.item.id", it.jsonPrimitive.content) }
        }
    }

    /**
     * Handles `GET /conversations/{conversation_id}/items` — lists items in a conversation
     * with cursor-based pagination.
     */
    private class ListItemsHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
            val params = request.url.parameters
            params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
            params.queryParameter("before")?.let { span.setAttribute("gen_ai.request.before", it) }
            params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
        }

        /** Response: `{ data: ConversationItem[], first_id, last_id, has_more, object }` */
        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
            body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
            body["has_more"]?.let { span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean) }
            val data = body["data"]
            span.setAttribute(
                "gen_ai.response.items_count",
                if (data is JsonArray) data.size.toLong() else 0L
            )
        }
    }

    /** Handles `GET /conversations/{conversation_id}/items/{item_id}` — retrieves a single item. */
    private class RetrieveItemHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
            extractItemIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.item.id", it) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.item.id", it.jsonPrimitive.content) }
            body["type"]?.let { span.setAttribute("openai.conversation.item.type", it.jsonPrimitive.content) }
            body["role"]?.let { span.setAttribute("openai.conversation.item.role", it.jsonPrimitive.content) }
        }
    }

    /** Handles `DELETE /conversations/{conversation_id}/items/{item_id}` — removes an item. */
    private class DeleteItemHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.id", it) }
            extractItemIdFromPath(request.url)
                ?.let { span.setAttribute("openai.conversation.item.id", it) }
        }

        /** Response: `{ id, deleted, object }` */
        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute("openai.conversation.item.id", it.jsonPrimitive.content) }
            body["deleted"]?.let { span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean) }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
