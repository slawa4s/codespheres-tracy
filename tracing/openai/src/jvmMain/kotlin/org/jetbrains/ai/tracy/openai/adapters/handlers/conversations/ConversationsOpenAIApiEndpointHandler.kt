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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API.
 *
 * Supports the following routes:
 * 1. `POST /conversations` → `conversations.create`
 * 2. `GET /conversations/{id}` → `conversations.retrieve`
 * 3. `DELETE /conversations/{id}` → `conversations.delete`
 * 4. `POST /conversations/{id}/items` → `conversations.items.create`
 * 5. `GET /conversations/{id}/items` → `conversations.items.list`
 * 6. `GET /conversations/{id}/items/{item_id}` → `conversations.items.retrieve`
 * 7. `DELETE /conversations/{id}/items/{item_id}` → `conversations.items.delete`
 *
 * Because [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
 * sets [GEN_AI_OPERATION_NAME] from the `object` field in the response body (e.g. `"conversation"`),
 * this handler explicitly re-sets [GEN_AI_OPERATION_NAME] at the **end** of [handleResponseAttributes]
 * so the correct route-derived value (e.g. `"conversations.create"`, `"conversations.items.list"`) always wins.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")

        val route = detectRoute(request.url, request.method)

        // For list routes, extract pagination query parameters
        if (route == ConversationRoute.ITEMS_LIST) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    /**
     * Sets response attributes and re-sets [GEN_AI_OPERATION_NAME] at the end so the
     * route-derived operation name overrides whatever [OpenAIApiUtils.setCommonResponseAttributes]
     * set from the response body's `object` field.
     */
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        val body = response.body.asJson()?.jsonObject ?: run {
            span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
            return
        }

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.RETRIEVE -> {
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.let { span.setAttribute("tracy.conversation.created_at", it.jsonPrimitive.content) }
            }

            ConversationRoute.DELETE -> {
                body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
                body["deleted"]?.let {
                    if (it.jsonPrimitive.boolean) {
                        span.setAttribute("tracy.conversation.deleted", "true")
                    }
                }
            }

            ConversationRoute.ITEMS_CREATE, ConversationRoute.ITEMS_LIST -> {
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("tracy.conversation.items.count", data.size.toLong())
                }
                body["first_id"]?.let { span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
                body["last_id"]?.let { span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
                body["has_more"]?.let { span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean) }
            }

            ConversationRoute.ITEMS_RETRIEVE, ConversationRoute.ITEMS_DELETE -> {
                body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
                body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
            }
        }

        // Always set operation name last so it overrides the value from setCommonResponseAttributes,
        // which sets GEN_AI_OPERATION_NAME from body["object"] (e.g. "conversation") rather than
        // the semantically meaningful route-derived name (e.g. "conversations.create").
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent event streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific conversations endpoint is being called based on URL path and HTTP method.
     *
     * Path structure after the "conversations" segment:
     * - (none)                      → CREATE (POST)
     * - {id}                        → RETRIEVE (GET) or DELETE
     * - {id}/items                  → ITEMS_CREATE (POST) or ITEMS_LIST (GET)
     * - {id}/items/{item_id}        → ITEMS_RETRIEVE (GET) or ITEMS_DELETE (DELETE)
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route: no 'conversations' segment in ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val afterConversations = segments.drop(conversationsIndex + 1)
        val hasConversationId = afterConversations.isNotEmpty() && afterConversations[0].isNotBlank()
        val hasItems = afterConversations.contains("items")
        val itemsIndex = afterConversations.indexOf("items")
        val hasItemId = hasItems && afterConversations.size > itemsIndex + 1 && afterConversations[itemsIndex + 1].isNotBlank()

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
