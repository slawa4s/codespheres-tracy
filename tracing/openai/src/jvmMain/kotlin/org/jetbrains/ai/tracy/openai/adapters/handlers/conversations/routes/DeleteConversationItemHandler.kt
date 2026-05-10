/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `DELETE /conversations/{conversation_id}/items/{item_id}` — removes an item.
 *
 * Request attributes:
 * - URL conversation ID → `gen_ai.conversation.id`
 * - URL item ID → `tracy.conversation.item.id`
 *
 * Response attributes:
 * - `created_at` → `tracy.conversation.created_at`
 */
internal class DeleteConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
        extractItemId(request.url)?.let { span.setAttribute("tracy.conversation.item.id", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
    }
}
