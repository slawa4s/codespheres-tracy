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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

private val logger = KotlinLogging.logger {}

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.UPDATE] endpoint: `POST /conversations/{conversation_id}`.
 *
 * Sets `gen_ai.operation.name=conversations.update` and `openai.api.type=conversations`.
 * Extracts the conversation ID from the URL path and from the response body.
 */
internal class UpdateConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        span.setAttribute("openai.api.type", "conversations")
        val id = extractConversationIdFromPath(request.url)
        if (id != null) {
            span.setAttribute("gen_ai.conversation.id", id)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute("gen_ai.conversation.id", it) }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.conversation.created_at", it) }
        // Override the value set by setCommonResponseAttributes (which reads body["object"])
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    private companion object {
        const val OPERATION_NAME = "conversations.update"
    }
}
