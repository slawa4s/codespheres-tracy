/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `DELETE /conversations/{id}` endpoint (route=DELETE).
 */
internal class DeleteConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "conversations")
    }

    /**
     * Response: `{ id, object: "conversation.deleted", deleted }`.
     *
     * Sets gen_ai.operation.name to "conversations.delete". The response body's object field
     * is "conversation.deleted", which is not a valid operation name.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.delete")
        val body = response.body.asJson()?.jsonObject ?: return
        body["deleted"]?.let { span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean) }
    }
}
