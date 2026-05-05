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

private const val OPERATION_NAME = "conversations.delete"
private val logger = KotlinLogging.logger {}

/**
 * Handles the `DELETE /conversations/{conversation_id}` endpoint.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations/delete)
 */
internal class DeleteConversationHandler : ConversationRouteHandler {

    /**
     * Sets [GEN_AI_OPERATION_NAME] and [OPENAI_API_TYPE] at request time, derived from the
     * HTTP method and path pattern — not from the response body's generic "object" field.
     */
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setConversationOperationAttributes(OPERATION_NAME)
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.request.conversation.requested_id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url.pathSegments.joinToString("/")}" }
        }
    }

    /**
     * Re-sets [GEN_AI_OPERATION_NAME] to ensure the correct value persists after
     * [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
     * may have overwritten it with the generic "conversation.deleted" string from the response body.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }
}
