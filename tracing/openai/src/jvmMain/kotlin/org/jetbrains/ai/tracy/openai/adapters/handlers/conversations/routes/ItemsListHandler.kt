/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
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
 * Handles the items.list endpoint: `GET /conversations/{conversation_id}/items`.
 *
 * Request:
 * - Extracts `conversation_id` from URL path → `gen_ai.conversation.id`
 * - Reads query params: `limit` → `tracy.request.limit`, `order` → `tracy.request.order`,
 *   `after` → `tracy.request.after`
 *
 * Response body:
 * - `data.size` → `tracy.conversation.items.count` (long)
 * - `first_id` → `tracy.conversation.items.first_id`
 * - `last_id` → `tracy.conversation.items.last_id`
 * - `has_more` → `tracy.conversation.items.has_more` (boolean)
 */
internal class ItemsListHandler : ConversationItemRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments.joinToString("/")}" }
        }

        val params = request.url.parameters
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        } else {
            span.setAttribute("tracy.conversation.items.count", 0L)
        }

        body["first_id"]?.let { span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content) }
        body["last_id"]?.let { span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content) }
        body["has_more"]?.let { span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean) }
    }
}
