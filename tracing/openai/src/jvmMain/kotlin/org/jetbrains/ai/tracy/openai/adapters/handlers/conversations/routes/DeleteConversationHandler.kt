/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles `DELETE /conversations/{conversation_id}` — deletes a conversation.
 *
 * Sets `gen_ai.operation.name = conversations.delete` and extracts `gen_ai.conversation.id`
 * from the URL on the request side. On response, `gen_ai.conversation.id` is read from the
 * body `id` field, `tracy.conversation.deleted` from the `deleted` boolean field, and the
 * operation name is re-applied to override the value written by `setCommonResponseAttributes`.
 */
internal class DeleteConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
        val conversationId = extractConversationIdFromPath(request.url)
        if (conversationId != null) {
            span.setAttribute(GEN_AI_CONVERSATION_ID, conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute(GEN_AI_CONVERSATION_ID, it.jsonPrimitive.content) }
        body["deleted"]?.let { span.setAttribute(TRACY_CONVERSATION_DELETED, it.jsonPrimitive.boolean) }
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)
    }

    companion object {
        private const val OPERATION_NAME = "conversations.delete"
        private val TRACY_CONVERSATION_DELETED: AttributeKey<Boolean> =
            AttributeKey.booleanKey("tracy.conversation.deleted")
    }
}
