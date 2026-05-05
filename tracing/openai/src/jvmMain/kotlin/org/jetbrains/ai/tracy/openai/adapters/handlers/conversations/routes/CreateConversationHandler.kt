/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.CREATE_CONVERSATION] endpoint:
 * `POST /conversations`.
 */
internal class CreateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No specific request attributes to extract for conversation creation
    }

    /**
     * Response: Conversation object containing id and created_at.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let {
            span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content)
        }

        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
    }
}
