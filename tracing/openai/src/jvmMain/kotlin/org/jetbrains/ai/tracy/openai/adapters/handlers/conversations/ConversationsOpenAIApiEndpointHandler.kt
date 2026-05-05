/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.core.policy.orRedactedOutput

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for managing stored conversations:
 * 1. `POST /v1/conversations` - Create a new conversation
 * 2. `GET /v1/conversations` - List conversations
 * 3. `GET /v1/conversations/{conversation_id}` - Get a conversation
 * 4. `DELETE /v1/conversations/{conversation_id}` - Delete a conversation
 * 5. `POST /v1/conversations/{conversation_id}/messages` - Add a message to a conversation
 * 6. `GET /v1/conversations/{conversation_id}/messages` - List messages in a conversation
 *
 * This handler ensures [GEN_AI_OPERATION_NAME] reflects the actual operation (e.g.,
 * "create_conversation", "list_conversations") rather than the raw `body["object"]`
 * values ("conversation" or "list") which are not meaningful operation names.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val route = detectRoute(request.url, request.method)
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val route = detectRoute(response.url, response.requestMethod)
        // Override the operation name set by setCommonResponseAttributes, which reads body["object"]
        // and produces non-descriptive values like "conversation" or "list".
        span.setAttribute(GEN_AI_OPERATION_NAME, route.operationName)

        val body = response.body.asJson()?.jsonObject ?: return
        when (route) {
            ConversationRoute.LIST, ConversationRoute.LIST_MESSAGES -> {
                val data = body["data"]
                if (data is JsonArray) {
                    span.setAttribute("gen_ai.response.count", data.size.toLong())
                }
                body["first_id"]?.let { span.setAttribute("gen_ai.response.first_id", it.jsonPrimitive.content) }
                body["last_id"]?.let { span.setAttribute("gen_ai.response.last_id", it.jsonPrimitive.content) }
                body["has_more"]?.let { span.setAttribute("gen_ai.response.has_more", it.jsonPrimitive.boolean) }
            }
            ConversationRoute.CREATE, ConversationRoute.GET -> {
                body["id"]?.let { span.setAttribute("gen_ai.response.conversation.id", it.jsonPrimitive.content) }
                body["status"]?.let { span.setAttribute("gen_ai.response.conversation.status", it.jsonPrimitive.content) }
            }
            ConversationRoute.ADD_MESSAGE -> {
                body["id"]?.let { span.setAttribute("gen_ai.response.message.id", it.jsonPrimitive.content) }
                body["role"]?.let { span.setAttribute("gen_ai.response.message.role", it.jsonPrimitive.content) }
                body["content"]?.let {
                    val content = when (it) {
                        is JsonPrimitive -> it.content
                        else -> it.toString()
                    }
                    span.setAttribute("gen_ai.response.message.content", content.orRedactedOutput())
                }
            }
            ConversationRoute.DELETE -> {
                body["deleted"]?.let { span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean) }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Detects which specific Conversations API endpoint is being called based on the URL path and HTTP method.
     */
    private fun detectRoute(url: TracyHttpUrl, method: String): ConversationRoute {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route. Endpoint has no `conversations` path segment: ${segments.joinToString(separator = "/")}" }
            return ConversationRoute.LIST
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()
        val hasMessagesSegment = segments.contains("messages")

        return when {
            method == "POST" && !hasConversationId -> ConversationRoute.CREATE
            method == "GET" && !hasConversationId -> ConversationRoute.LIST
            method == "GET" && hasConversationId && !hasMessagesSegment -> ConversationRoute.GET
            method == "DELETE" && hasConversationId -> ConversationRoute.DELETE
            method == "POST" && hasConversationId && hasMessagesSegment -> ConversationRoute.ADD_MESSAGE
            method == "GET" && hasConversationId && hasMessagesSegment -> ConversationRoute.LIST_MESSAGES
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString(separator = "/")}" }
                ConversationRoute.LIST
            }
        }
    }

    /**
     * Internal enum to distinguish between different Conversations API routes.
     */
    private enum class ConversationRoute(val operationName: String) {
        CREATE("create_conversation"),
        GET("get_conversation"),
        LIST("list_conversations"),
        DELETE("delete_conversation"),
        ADD_MESSAGE("add_message"),
        LIST_MESSAGES("list_messages"),
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
