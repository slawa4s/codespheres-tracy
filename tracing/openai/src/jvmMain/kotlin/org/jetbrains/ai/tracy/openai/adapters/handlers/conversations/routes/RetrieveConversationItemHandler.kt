/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `GET /conversations/{conversation_id}/items/{item_id}` — retrieves a single item.
 *
 * Request attributes:
 * - URL conversation ID → `gen_ai.conversation.id`
 *
 * Response attributes:
 * - `id` → `tracy.conversation.item.id`
 * - `type` → `tracy.conversation.item.type`
 * - `status` → `tracy.conversation.item.status`
 */
internal class RetrieveConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.id", it) }
        body["type"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.type", it) }
        body["status"]?.jsonPrimitive?.content?.let { span.setAttribute("tracy.conversation.item.status", it) }
    }
}
