/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

private val logger = KotlinLogging.logger {}

/**
 * Handles the items.create endpoint: `POST /conversations/{conversation_id}/items`.
 *
 * Extracts `conversation_id` from the URL path → `gen_ai.conversation.id`.
 */
internal class ItemsCreateHandler : ConversationItemRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments.joinToString("/")}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // conversation_id already captured from the request URL
    }
}
