/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API provides endpoints for creating and managing multi-turn conversations:
 * - `POST /conversations` — Create a new conversation
 * - `GET /conversations` — List all conversations
 * - `GET /conversations/{conversation_id}` — Retrieve a specific conversation
 * - `DELETE /conversations/{conversation_id}` — Delete a conversation
 *
 * This handler sets the correct [GEN_AI_OPERATION_NAME] and the `openai.api.type` attribute,
 * preventing wrong values (e.g. `"conversation"` or `"list"` from the raw `object` field)
 * from leaking into spans when the request falls through to the default handler.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute(GEN_AI_OPERATION_NAME, detectOperationName(request.url, request.method))
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Override the operation name that setCommonResponseAttributes set from the raw `object` field.
        span.setAttribute(GEN_AI_OPERATION_NAME, detectOperationName(response.url, response.requestMethod))
        span.setAttribute("openai.api.type", "conversations")

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming.
    }

    /**
     * Derives a meaningful operation name from the URL path and HTTP method.
     *
     * | Method | Path                          | Operation             |
     * |--------|-------------------------------|-----------------------|
     * | POST   | /conversations                | create_conversation   |
     * | GET    | /conversations                | list_conversations    |
     * | GET    | /conversations/{id}           | get_conversation      |
     * | DELETE | /conversations/{id}           | delete_conversation   |
     */
    private fun detectOperationName(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        val hasConversationId = conversationsIndex != -1 &&
                segments.size > conversationsIndex + 1 &&
                segments[conversationsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> "create_conversation"
            method == "GET" && hasConversationId -> "get_conversation"
            method == "GET" && !hasConversationId -> "list_conversations"
            method == "DELETE" && hasConversationId -> "delete_conversation"
            else -> "conversation"
        }
    }
}
