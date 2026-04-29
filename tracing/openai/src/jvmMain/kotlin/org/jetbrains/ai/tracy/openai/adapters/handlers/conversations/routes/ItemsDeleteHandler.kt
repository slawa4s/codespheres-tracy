/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles the ITEMS_DELETE endpoint: `DELETE /conversations/{conversation_id}/items/{item_id}`.
 *
 * Request attributes set:
 * - `gen_ai.conversation.id` from URL path
 * - `tracy.conversation.item.id` from URL path
 *
 * Response attributes set:
 * - `tracy.conversation.created_at` from the deleted item's `created_at` field in the response body
 */
internal class ItemsDeleteHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments}" }
        }

        val itemId = extractItemIdFromPath(request.url)
        if (itemId != null) {
            span.setAttribute("tracy.conversation.item.id", itemId)
        } else {
            logger.warn { "Failed to extract item ID from URL: ${request.url.pathSegments}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
    }
}
