/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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
 * The Conversations API provides endpoints for managing conversation threads:
 * 1. `POST /conversations` - Create a new conversation
 * 2. `GET /conversations/{conversation_id}` - Get a specific conversation
 * 3. `GET /conversations` - List all conversations
 * 4. `DELETE /conversations/{conversation_id}` - Delete a conversation
 *
 * The response body `object` field carries raw values like 'conversation', 'list',
 * or 'conversation.deleted'. This handler overrides the `gen_ai.operation.name`
 * attribute (set by [OpenAIApiUtils.setCommonResponseAttributes] from the `object` field)
 * with a clean, route-derived operation name.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)

        when (route) {
            ConversationRoute.CREATE -> {
                val body = request.body.asJson()?.jsonObject ?: return
                body["model"]?.let { span.setAttribute("gen_ai.request.model", it.jsonPrimitive.content) }
                body["metadata"]?.let { span.setAttribute("gen_ai.request.metadata", it.toString()) }
            }

            ConversationRoute.GET, ConversationRoute.DELETE -> {
                val conversationId = extractConversationIdFromPath(request.url)
                if (conversationId != null) {
                    span.setAttribute("gen_ai.request.conversation.id", conversationId)
                } else {
                    logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
                }
            }

            ConversationRoute.LIST -> {
                val params = request.url.parameters
                params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
                params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
                params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)

        // Override the raw `object` value ('conversation', 'list', 'conversation.deleted')
        // that OpenAIApiUtils.setCommonResponseAttributes set before this handler runs.
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return

        when (route) {
            ConversationRoute.CREATE, ConversationRoute.GET -> {
                body["id"]?.let { span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content) }
                body["created_at"]?.jsonPrimitive?.longOrNull?.let {
                    span.setAttribute("gen_ai.response.conversation.created_at", it)
                }
                body["metadata"]?.let { span.setAttribute("gen_ai.response.conversation.metadata", it.toString()) }
            }

            ConversationRoute.LIST -> {
                body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
                body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
                body["has_more"]?.let { span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean) }
            }

            ConversationRoute.DELETE -> {
                body["id"]?.let { span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content) }
                body["deleted"]?.let { span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which Conversations endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val containsConversationId = extractConversationIdFromPath(url) != null

        return when {
            method == "POST" && !containsConversationId -> ConversationRoute.CREATE
            method == "GET" && containsConversationId -> ConversationRoute.GET
            method == "GET" && !containsConversationId -> ConversationRoute.LIST
            method == "DELETE" && containsConversationId -> ConversationRoute.DELETE
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${url.pathSegments.joinToString(separator = "/")}" }
                ConversationRoute.CREATE
            }
        }
    }

    /**
     * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}`.
     */
    private fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val potentialId = segments[conversationsIndex + 1]
        return potentialId.takeIf { it.isNotBlank() && it != "conversations" }
    }

    /**
     * Routes for the Conversations API with their semantic operation names.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("create_conversation"),
        GET("get_conversation"),
        LIST("list_conversations"),
        DELETE("delete_conversation"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
