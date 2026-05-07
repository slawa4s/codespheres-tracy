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
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

private val logger = KotlinLogging.logger {}

/**
 * Handles the `DELETE /conversations/{conv_id}/items/{item_id}` endpoint: deletes a conversation item.
 *
 * Sets:
 * - `gen_ai.operation.name` = `conversations.items.delete`
 * - `openai.api.type` = `conversations`
 * - `gen_ai.conversation.id` from the URL path
 */
internal class DeleteConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.delete")
        span.setAttribute(OPENAI_API_TYPE_KEY, OPENAI_API_TYPE_VALUE)
        val convId = extractConversationIdFromPath(request.url)
        if (convId != null) {
            span.setAttribute(CONVERSATION_ID_KEY, convId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${request.url}" }
        }
        val itemId = extractItemIdFromPath(request.url)
        if (itemId != null) {
            span.setAttribute("gen_ai.request.item.id", itemId)
        } else {
            logger.warn { "Failed to extract item ID from URL: ${request.url}" }
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["deleted"]?.let { span.setAttribute("gen_ai.response.deleted", it.jsonPrimitive.boolean) }
    }
}
