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
 * Handles POST /conversations/{id}/items — creates a new item in a conversation.
 *
 * Sets `gen_ai.operation.name = "conversations.items.create"` on the response span,
 * overriding the value written by `setCommonResponseAttributes`.
 *
 * Request attributes extracted:
 * - conversation ID from URL path → `gen_ai.conversation.id`
 */
internal class CreateConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments.joinToString("/")}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.create")
    }
}
