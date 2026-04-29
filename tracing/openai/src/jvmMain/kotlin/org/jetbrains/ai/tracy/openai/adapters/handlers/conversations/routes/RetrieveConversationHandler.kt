/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.RETRIEVE] endpoint:
 * `GET /conversations/{conversation_id}`.
 */
internal class RetrieveConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setConversationApiType()
        span.setConversationOperationName("conversations.retrieve")
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
    }

    /**
     * Response: Conversation object.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.setConversationApiType()
        span.setConversationOperationName("conversations.retrieve")
        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
    }
}
