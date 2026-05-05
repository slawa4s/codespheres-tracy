/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

private const val OPERATION_NAME = "conversations.create"

/**
 * Handles the `POST /conversations` endpoint.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations/create)
 */
internal class CreateConversationHandler : ConversationRouteHandler {

    /**
     * Sets [GEN_AI_OPERATION_NAME] and [OPENAI_API_TYPE] at request time, derived from the
     * HTTP method and path pattern — not from the response body's generic "object" field.
     */
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setConversationOperationAttributes(OPERATION_NAME)
    }

    /**
     * Re-sets [GEN_AI_OPERATION_NAME] to ensure the correct value persists after
     * [org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils.setCommonResponseAttributes]
     * may have overwritten it with the generic "conversation" string from the response body.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }
}
