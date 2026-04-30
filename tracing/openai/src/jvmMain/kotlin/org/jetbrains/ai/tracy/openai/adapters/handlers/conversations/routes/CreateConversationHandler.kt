/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.CREATE] endpoint:
 * `POST /conversations`.
 */
internal class CreateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        body["metadata"]?.let { span.setAttribute("gen_ai.request.metadata", it.toString()) }
    }

    /**
     * Response: Conversation object `{ id, object: "conversation", created_at, metadata }`.
     *
     * Sets gen_ai.operation.name to "conversations.create" and gen_ai.conversation.id from
     * body["id"] instead of using gen_ai.response.id.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.create")
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
        body["created_at"]?.let { span.setAttribute("gen_ai.response.created_at", it.toString()) }
        body["metadata"]?.let { span.setAttribute("gen_ai.response.metadata", it.toString()) }
    }
}
