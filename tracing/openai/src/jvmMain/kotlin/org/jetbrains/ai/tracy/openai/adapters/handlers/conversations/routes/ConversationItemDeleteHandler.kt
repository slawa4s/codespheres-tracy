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
 * Handles the `DELETE /conversations/{conversation_id}/items/{item_id}` endpoint.
 *
 * Sets [GEN_AI_OPERATION_NAME] to `conversations.items.delete`, captures the
 * conversation ID and item ID from the URL path. The response body is the parent
 * conversation object — its `id` is written to `gen_ai.conversation.id` and
 * `created_at` to `tracy.conversation.created_at`. The URL-extracted item ID is
 * written to `tracy.conversation.item.id`.
 */
internal class ConversationItemDeleteHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.delete")
        extractConversationIdFromPath(request.url)
            ?.let { span.setAttribute("gen_ai.conversation.id", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.delete")
        val body = response.body.asJson()?.jsonObject ?: return
        // Response body is the parent conversation object
        body["id"]?.let { span.setAttribute("gen_ai.conversation.id", it.jsonPrimitive.content) }
        body["created_at"]?.jsonPrimitive?.longOrNull
            ?.let { span.setAttribute("tracy.conversation.created_at", it) }
        // Item ID is extracted from the URL path segment after "items"
        extractItemIdFromPath(response.url)
            ?.let { span.setAttribute("tracy.conversation.item.id", it) }
    }
}
