/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationItemRoute.RETRIEVE_ITEM] endpoint:
 * `GET /conversations/{conversation_id}/items/{item_id}`.
 *
 * Sets [GEN_AI_OPERATION_NAME] to `conversations.items.retrieve`, traces conversation ID from the
 * URL, and on response reads `id`, `type`, `status`, and `conversation_id` (falling back to URL).
 */
internal class RetrieveConversationItemHandler : ConversationItemRouteHandler {

    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute("openai.api.type", "conversations")
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute(GEN_AI_CONVERSATION_ID, it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
        body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
        val conversationId = body["conversation_id"]?.jsonPrimitive?.content
            ?: extractConversationIdFromPath(response.url)
        conversationId?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    companion object {
        const val OPERATION_NAME = "conversations.items.retrieve"
    }
}
