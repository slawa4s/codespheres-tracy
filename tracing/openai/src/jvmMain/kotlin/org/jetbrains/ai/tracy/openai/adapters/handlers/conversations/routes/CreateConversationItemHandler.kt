/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.CREATE_ITEM] endpoint:
 * `POST /conversations/{conversation_id}/items`.
 */
internal class CreateConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setConversationApiType()
        span.setConversationOperationName("conversations.items.create")
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
    }

    /**
     * Response: List of conversation items with pagination metadata.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.setConversationApiType()
        span.setConversationOperationName("conversations.items.create")
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }
        body["first_id"]?.let { span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
        body["has_more"]?.let { span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean) }
    }
}
