/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `DELETE /conversations/{conversation_id}/items/{item_id}` — removes an item.
 *
 * Sets `gen_ai.operation.name=conversations.items.delete`, `openai.api.type=conversations`,
 * and `gen_ai.conversation.id` from URL. On response reads `id→tracy.conversation.item.id`
 * and `created_at→tracy.conversation.created_at`.
 */
internal class DeleteConversationItemHandler : ConversationRouteHandler {

    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute("openai.api.type", "conversations")
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
        extractConversationIdFromPath(response.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    companion object {
        const val OPERATION_NAME = "conversations.items.delete"
    }
}
