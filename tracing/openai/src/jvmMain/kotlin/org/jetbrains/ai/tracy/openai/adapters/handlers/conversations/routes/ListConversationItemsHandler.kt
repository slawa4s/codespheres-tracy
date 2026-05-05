/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `GET /conversations/{id}/items` (conversations.items.list).
 */
internal class ListConversationItemsHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.list")
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
        val params = request.url.parameters
        params.queryParameter("limit")?.let { span.setAttribute("tracy.conversation.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.conversation.request.order", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["has_more"]?.let {
            span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
        }
        body["first_id"]?.let {
            span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content)
        }
        body["last_id"]?.let {
            span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content)
        }
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }
    }
}
