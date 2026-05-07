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
 * Handles the delete-conversation-item endpoint:
 * `DELETE /realtime/conversations/{conversation_id}/items/{item_id}`.
 *
 * Response: deletion confirmation → `tracy.conversation.item.*`
 */
internal class DeleteConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val itemId = extractItemIdFromPath(request.url)
        if (itemId != null) {
            span.setAttribute("tracy.conversation.item.id", itemId)
        } else {
            logger.warn { "Failed to extract item ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.setSingleItemAttributes(body)
    }
}
