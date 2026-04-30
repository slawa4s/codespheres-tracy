/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
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
 * Handler for the OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing conversation sessions and their items:
 * 1. `POST /conversations` — Create a conversation (`conversations.create`)
 * 2. `GET /conversations` — List conversations (`conversations.list`)
 * 3. `GET /conversations/{id}` — Get a conversation (`conversations.get`)
 * 4. `DELETE /conversations/{id}` — Delete a conversation (`conversations.delete`)
 * 5. `GET /conversations/{id}/items` — List conversation items (`conversations.items.list`)
 * 6. `DELETE /conversations/{id}/items/{item_id}` — Delete a conversation item (`conversations.items.delete`)
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
        val body = request.body.asJson()?.jsonObject

        body?.get("model")?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content)
        }
        body?.get("modalities")?.let {
            span.setAttribute("gen_ai.request.modalities", it.toString())
        }
        body?.get("instructions")?.let {
            span.setAttribute("gen_ai.request.instructions", it.jsonPrimitive.content)
        }

        // Query parameters for list endpoints
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("before")?.let { span.setAttribute("gen_ai.request.before", it) }
        params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
    }

    /**
     * Sets response attributes and re-sets [GEN_AI_OPERATION_NAME] at the end so the
     * route-derived operation name overrides whatever [OpenAIApiUtils.setCommonResponseAttributes]
     * set from the response body's `object` field.
     */
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject
        val route = detectRoute(response.url, response.requestMethod)

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.GET -> {
                if (body != null) traceConversation(span, body)
            }

            ConversationRoute.LIST -> {
                if (body != null) {
                    body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
                    body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
                    body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.has_more", it)
                    }
                    val data = body["data"]
                    if (data is JsonArray) {
                        span.setAttribute("gen_ai.response.conversations_count", data.size.toLong())
                    }
                }
            }

            ConversationRoute.DELETE -> {
                if (body != null) {
                    body["id"]?.let { span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content) }
                    body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.deleted", it)
                    }
                }
            }

            ConversationRoute.ITEMS_LIST -> {
                if (body != null) {
                    body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
                    body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
                    body["has_more"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.has_more", it)
                    }
                    val data = body["data"]
                    if (data is JsonArray) {
                        span.setAttribute("gen_ai.response.items_count", data.size.toLong())
                    }
                }
            }

            ConversationRoute.ITEMS_DELETE -> {
                if (body != null) {
                    body["id"]?.let { span.setAttribute("gen_ai.response.item.id", it.jsonPrimitive.content) }
                    body["deleted"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("gen_ai.response.deleted", it)
                    }
                }
            }
        }

        // Always set operation name last so it overrides the value from setCommonResponseAttributes,
        // which sets GEN_AI_OPERATION_NAME from body["object"] (e.g. "conversation") rather than
        // the semantically meaningful route-derived name (e.g. "conversations.create").
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming in the same way as chat completions
        logger.warn { "Conversations API does not support server-sent events streaming" }
    }

    private fun traceConversation(span: Span, body: JsonObject) {
        body["id"]?.let { span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content) }
        body["status"]?.let { span.setAttribute("gen_ai.response.conversation.status", it.jsonPrimitive.content) }
        body["model"]?.let { span.setAttribute("gen_ai.response.model", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.created_at", it)
        }
        body["expires_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.conversation.expires_at", it)
        }
    }

    /**
     * Detects which specific conversation endpoint is being called.
     *
     * URL shapes:
     * - `POST  /conversations`                      → CREATE
     * - `GET   /conversations`                      → LIST
     * - `GET   /conversations/{id}`                 → GET
     * - `DELETE /conversations/{id}`                → DELETE
     * - `GET   /conversations/{id}/items`           → ITEMS_LIST
     * - `DELETE /conversations/{id}/items/{item_id}` → ITEMS_DELETE
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val convsIndex = segments.indexOf("conversations")
        if (convsIndex == -1) {
            logger.warn { "Failed to detect conversation route — no 'conversations' segment: ${segments.joinToString("/")}" }
            return ConversationRoute.CREATE
        }

        val hasConvId = segments.size > convsIndex + 1 && segments[convsIndex + 1].isNotBlank()
        val hasItems = segments.contains("items")
        val hasItemId = hasItems && run {
            val itemsIndex = segments.indexOf("items")
            segments.size > itemsIndex + 1 && segments[itemsIndex + 1].isNotBlank()
        }

        return when {
            method == "POST" && !hasConvId -> ConversationRoute.CREATE
            method == "GET" && !hasConvId -> ConversationRoute.LIST
            method == "GET" && hasConvId && !hasItems -> ConversationRoute.GET
            method == "DELETE" && hasConvId && !hasItems -> ConversationRoute.DELETE
            method == "GET" && hasConvId && hasItems -> ConversationRoute.ITEMS_LIST
            method == "DELETE" && hasConvId && hasItems && hasItemId -> ConversationRoute.ITEMS_DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Routes for the Conversations API, each carrying its canonical operation name.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("conversations.create"),
        LIST("conversations.list"),
        GET("conversations.get"),
        DELETE("conversations.delete"),
        ITEMS_LIST("conversations.items.list"),
        ITEMS_DELETE("conversations.items.delete"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
