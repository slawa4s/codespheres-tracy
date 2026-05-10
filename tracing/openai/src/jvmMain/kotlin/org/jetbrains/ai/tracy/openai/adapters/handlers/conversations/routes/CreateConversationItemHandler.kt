/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `POST /conversations/{conversation_id}/items` — appends items to a Conversation.
 *
 * Request attributes:
 * - URL conversation ID → `gen_ai.conversation.id`
 *
 * Response attributes:
 * - `data` array size → `tracy.conversation.items.count`
 * - `data[0].id` → `tracy.conversation.items.first_id`
 * - `data[last].id` → `tracy.conversation.items.last_id`
 */
internal class CreateConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationId(request.url)?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
            (data.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.conversation.items.first_id", it)
            }
            (data.lastOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.conversation.items.last_id", it)
            }
        } else {
            span.setAttribute("tracy.conversation.items.count", 0L)
        }
    }
}
