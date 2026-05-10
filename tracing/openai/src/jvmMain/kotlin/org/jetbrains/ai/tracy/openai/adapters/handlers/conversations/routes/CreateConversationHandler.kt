/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles `POST /conversations` — creates a new conversation.
 *
 * Sets `gen_ai.operation.name = conversations.create`, `gen_ai.conversation.id` from the
 * response body `id` field, and `tracy.conversation.created_at` from the `created_at` field.
 * The operation name is re-applied on response to override the value written by
 * `setCommonResponseAttributes` from the response `object` field.
 */
internal class CreateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    companion object {
        private const val OPERATION_NAME = "conversations.create"
    }
}
