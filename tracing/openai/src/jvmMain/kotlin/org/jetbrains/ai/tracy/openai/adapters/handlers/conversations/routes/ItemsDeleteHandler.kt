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
 * Handles the items.delete endpoint: `DELETE /conversations/{conversation_id}/items/{item_id}`.
 *
 * Request:
 * - Extracts `conversation_id` from URL path → `gen_ai.conversation.id`
 *
 * Response body:
 * - `id` → `tracy.conversation.item.id`
 * - `created_at` → `tracy.conversation.created_at` (long)
 */
internal class ItemsDeleteHandler : ConversationItemRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments.joinToString("/")}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
    }
}
