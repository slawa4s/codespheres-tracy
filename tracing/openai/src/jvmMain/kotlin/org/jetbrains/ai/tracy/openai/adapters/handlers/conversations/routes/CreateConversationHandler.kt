/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles POST /conversations — creates a new conversation.
 *
 * Sets `gen_ai.operation.name = "conversations.create"` on the response span,
 * overriding the value written by `setCommonResponseAttributes`.
 *
 * Response attributes extracted:
 * - `body["id"]` → `gen_ai.conversation.id`
 * - `body["created_at"]` → `tracy.conversation.created_at`
 */
internal class CreateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No conversation ID in path for POST /conversations (it doesn't exist yet)
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.create")
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
        body["created_at"]?.let { span.setAttribute("tracy.conversation.created_at", it.jsonPrimitive.content) }
    }
}
