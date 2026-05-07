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
 * Handles the `POST /conversations` endpoint: creates a new conversation.
 *
 * Sets:
 * - `gen_ai.operation.name` = `conversations.create`
 * - `openai.api.type` = `conversations`
 * - `gen_ai.conversation.id` from the response body `id` field
 */
internal class CreateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute(OPENAI_API_TYPE_KEY, OPENAI_API_TYPE_VALUE)
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute(CONVERSATION_ID_KEY, it)
        }
    }
}

internal const val OPERATION_NAME = "conversations.create"
internal const val OPENAI_API_TYPE_KEY = "openai.api.type"
internal const val OPENAI_API_TYPE_VALUE = "conversations"
internal const val CONVERSATION_ID_KEY = "gen_ai.conversation.id"
