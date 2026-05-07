/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `GET /conversations/{conversation_id}` — retrieve an existing conversation.
 */
internal class RetrieveConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.request.conversation.requested_id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceConversation(body, "gen_ai.response.conversation")
        span.setAttribute("gen_ai.operation.name", "retrieve_conversation")
    }
}
