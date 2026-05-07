/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

private val logger = KotlinLogging.logger {}

/**
 * Handles the `POST /conversations/{conv_id}` endpoint: updates a conversation.
 *
 * Sets:
 * - `gen_ai.operation.name` = `conversations.update`
 * - `openai.api.type` = `conversations`
 * - `gen_ai.conversation.id` from the URL path
 */
internal class UpdateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.update")
        span.setAttribute(OPENAI_API_TYPE_KEY, OPENAI_API_TYPE_VALUE)
        val convId = extractConversationIdFromPath(request.url)
        if (convId != null) {
            span.setAttribute(CONVERSATION_ID_KEY, convId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // Conversation ID already set from request path
    }
}
