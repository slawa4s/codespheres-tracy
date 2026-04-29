/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles the ITEMS_CREATE endpoint: `POST /conversations/{conversation_id}/items`.
 *
 * Request attributes set:
 * - `gen_ai.conversation.id` from URL path
 *
 * Response attributes set:
 * - `tracy.conversation.items.count` as `data` array size
 * - `tracy.conversation.items.first_id` from response body `first_id`
 * - `tracy.conversation.items.last_id` from response body `last_id`
 */
internal class ItemsCreateHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.setConversationItemsResponseAttributes(body)
    }
}
