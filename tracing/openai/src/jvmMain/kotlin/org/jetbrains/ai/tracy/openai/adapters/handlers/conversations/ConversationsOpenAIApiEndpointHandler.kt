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
 * The Conversations API provides multiple endpoints for managing conversations and their items:
 * 1. `POST /conversations` — Create a new conversation (conversations.create)
 * 2. `POST /conversations/{id}/items` — Append items to a conversation (conversations.items.create)
 * 3. `GET /conversations/{id}/items` — List items in a conversation (conversations.items.list)
 * 4. `GET /conversations/{id}/items/{item_id}` — Retrieve a specific item (conversations.items.retrieve)
 * 5. `DELETE /conversations/{id}/items/{item_id}` — Delete a specific item (conversations.items.delete)
 * 6. `DELETE /conversations/{id}` — Delete a conversation (conversations.delete)
 *
 * This handler detects the specific route from the HTTP method and URL path segments,
 * then traces request/response attributes accordingly.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")

        val segments = request.url.pathSegments
        val convIndex = segments.indexOf("conversations")
        if (convIndex != -1 && segments.size > convIndex + 1) {
            val conversationId = segments[convIndex + 1]
            if (conversationId.isNotBlank()) {
                span.setAttribute("gen_ai.conversation.id", conversationId)
            }
        }

        val params = request.url.parameters
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)

        // Override gen_ai.operation.name with the route-derived value, not the response "object" field
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }

        when (route) {
            ConversationRoute.ITEMS_LIST, ConversationRoute.ITEMS_CREATE -> {
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

            ConversationRoute.ITEMS_RETRIEVE -> {
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }

            ConversationRoute.DELETE -> {
                span.setAttribute("tracy.conversation.deleted", true)
            }

            else -> {}
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversation endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convIndex = segments.indexOf("conversations")

        if (convIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.CREATE
        }

        val hasId = segments.size > convIndex + 1 && segments[convIndex + 1].isNotBlank()
        val itemsIndex = if (hasId) segments.indexOf("items").takeIf { it > convIndex } ?: -1 else -1
        val hasItems = itemsIndex != -1
        val hasItemId = hasItems && segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasId -> ConversationRoute.CREATE
            method == "POST" && hasId && hasItems -> ConversationRoute.ITEMS_CREATE
            method == "GET" && hasId && hasItems && !hasItemId -> ConversationRoute.ITEMS_LIST
            method == "GET" && hasId && hasItems && hasItemId -> ConversationRoute.ITEMS_RETRIEVE
            method == "DELETE" && hasId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            method == "DELETE" && hasId && !hasItems -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Internal enum to distinguish between different conversation API routes.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        ITEMS_CREATE("conversations.items.create"),
        ITEMS_LIST("conversations.items.list"),
        ITEMS_RETRIEVE("conversations.items.retrieve"),
        ITEMS_DELETE("conversations.items.delete"),
        DELETE("conversations.delete"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
