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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles `GET /conversations/{id}/items` endpoint: lists items in a conversation.
 */
internal class ListConversationItemsHandler : ConversationRouteHandler {

    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.list")

        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }

        val params = request.url.parameters
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
    }

    /**
     * Response: `{ data: Item[], first_id, last_id, has_more }`
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data != null && data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        } else {
            span.setAttribute("tracy.conversation.items.count", 0L)
        }

        body["first_id"]?.let {
            span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content)
        }
        body["last_id"]?.let {
            span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content)
        }
        body["has_more"]?.let {
            span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
        }
    }
}
