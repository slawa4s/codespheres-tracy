/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `conversations.delete` endpoint: `DELETE /conversations/{conversation_id}`.
 */
internal class DeleteConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // Conversation ID is in the path and already used for route detection
    }

    /**
     * Response: { id, deleted, object }
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
        body["deleted"]?.let { span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
    }
}
