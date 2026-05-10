/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
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
 * Detects all 8 Conversations API routes from the URL path and HTTP method, sets
 * `openai.api.type = "conversations"` and the correct `gen_ai.operation.name` on every span,
 * and delegates per-route attribute extraction to a registry of [ConversationRouteHandler] instances.
 *
 * Supported routes:
 * 1. `POST /conversations` → `conversations.create`
 * 2. `GET /conversations/{id}` → `conversations.retrieve`
 * 3. `POST /conversations/{id}` → `conversations.update`
 * 4. `DELETE /conversations/{id}` → `conversations.delete`
 * 5. `POST /conversations/{id}/items` → `conversations.items.create`
 * 6. `GET /conversations/{id}/items` → `conversations.items.list`
 * 7. `GET /conversations/{id}/items/{item_id}` → `conversations.items.retrieve`
 * 8. `DELETE /conversations/{id}/items/{item_id}` → `conversations.items.delete`
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Handles requests and responses for a specific Conversations API route.
     */
    private interface ConversationRouteHandler {
        fun handleRequest(span: Span, request: TracyHttpRequest)
        fun handleResponse(span: Span, response: TracyHttpResponse)
    }

    /**
     * All supported Conversations API routes with their OTel operation names.
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

    private val routeHandlers: Map<ConversationRoute, ConversationRouteHandler> by lazy {
        mapOf(
            ConversationRoute.CREATE to CreateConversationHandler(),
            ConversationRoute.RETRIEVE to RetrieveConversationHandler(),
            ConversationRoute.UPDATE to UpdateConversationHandler(),
            ConversationRoute.DELETE to DeleteConversationHandler(),
            ConversationRoute.ITEMS_CREATE to ItemsCreateHandler(),
            ConversationRoute.ITEMS_LIST to ItemsListHandler(),
            ConversationRoute.ITEMS_RETRIEVE to ItemsRetrieveHandler(),
            ConversationRoute.ITEMS_DELETE to ItemsDeleteHandler(),
        )
    }

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleRequest(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
        routeHandlers[route]?.handleResponse(span, response)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not support SSE streaming" }
    }

    /**
     * Detects the Conversations API route by locating the "conversations" segment in the URL path
     * and branching on the HTTP method and whether a conversation ID, "items" segment, or item ID
     * follow it.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "No 'conversations' path segment in URL: ${segments.joinToString("/")}" }
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
            method == "POST" && hasItemsSegment -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasItemsSegment && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasItemsSegment && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Unrecognized conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    // ===== Per-route handlers =====

    /** Handles `POST /conversations`: extracts model from request body, conversation ID from response. */
    private class CreateConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            val body = request.body.asJson()?.jsonObject ?: return
            body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
            body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("tracy.conversation.created_at", it)
            }
        }
    }

    /** Handles `GET /conversations/{id}`: traces the requested conversation ID and response. */
    private class RetrieveConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        }
    }

    /** Handles `POST /conversations/{id}`: traces the updated conversation ID. */
    private class UpdateConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        }
    }

    /** Handles `DELETE /conversations/{id}`: traces the deleted conversation ID and deletion status. */
    private class DeleteConversationHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
            body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.deleted", it)
            }
        }
    }

    /** Handles `POST /conversations/{id}/items`: traces conversation ID and item type/role from request. */
    private class ItemsCreateHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
            val body = request.body.asJson()?.jsonObject ?: return
            body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
            body["role"]?.let { span.setAttribute("tracy.conversation.item.role", it.jsonPrimitive.content) }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
        }
    }

    /** Handles `GET /conversations/{id}/items`: traces conversation ID and item count from response. */
    private class ItemsListHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            val data = body["data"]
            if (data is JsonArray) {
                span.setAttribute("tracy.conversation.items_count", data.size.toLong())
            }
            body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.has_more", it)
            }
        }
    }

    /** Handles `GET /conversations/{id}/items/{item_id}`: traces conversation and item IDs plus item metadata. */
    private class ItemsRetrieveHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
            extractItemId(request.url)?.let {
                span.setAttribute("tracy.conversation.item.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
            body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
            body["role"]?.let { span.setAttribute("tracy.conversation.item.role", it.jsonPrimitive.content) }
        }
    }

    /** Handles `DELETE /conversations/{id}/items/{item_id}`: traces conversation and item IDs plus deletion status. */
    private class ItemsDeleteHandler : ConversationRouteHandler {
        override fun handleRequest(span: Span, request: TracyHttpRequest) {
            extractConversationId(request.url)?.let {
                span.setAttribute("tracy.conversation.id", it)
            }
            extractItemId(request.url)?.let {
                span.setAttribute("tracy.conversation.item.id", it)
            }
        }

        override fun handleResponse(span: Span, response: TracyHttpResponse) {
            val body = response.body.asJson()?.jsonObject ?: return
            body["id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it.jsonPrimitive.content) }
            body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.deleted", it)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

// ===== File-level helpers (accessible to all nested classes in this file) =====

private fun extractConversationId(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")
    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val id = segments[conversationsIndex + 1]
        if (id.isNotBlank()) id else null
    } else null
}

private fun extractItemId(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    return if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
        val id = segments[itemsIndex + 1]
        if (id.isNotBlank()) id else null
    } else null
}
