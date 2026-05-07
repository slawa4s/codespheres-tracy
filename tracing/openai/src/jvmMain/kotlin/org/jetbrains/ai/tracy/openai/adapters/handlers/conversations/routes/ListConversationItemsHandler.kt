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
 * Handles the `GET /conversations/{conv_id}/items` endpoint: lists conversation items.
 *
 * Sets:
 * - `gen_ai.operation.name` = `conversations.items.list`
 * - `openai.api.type` = `conversations`
 * - `gen_ai.conversation.id` from the URL path
 */
internal class ListConversationItemsHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.list")
        span.setAttribute(OPENAI_API_TYPE_KEY, OPENAI_API_TYPE_VALUE)
        val convId = extractConversationIdFromPath(request.url)
        if (convId != null) {
            span.setAttribute(CONVERSATION_ID_KEY, convId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        val params = request.url.parameters
        params.queryParameter("after")?.let { span.setAttribute("gen_ai.request.after", it) }
        params.queryParameter("limit")?.let { span.setAttribute("gen_ai.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("gen_ai.request.order", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["first_id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.response.first_id", it) }
        body["last_id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.response.last_id", it) }
        body["has_more"]?.jsonPrimitive?.boolean?.let { span.setAttribute("gen_ai.response.has_more", it) }
        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("gen_ai.response.items_count", data.size.toLong())
        } else {
            span.setAttribute("gen_ai.response.items_count", 0L)
        }
    }
}
