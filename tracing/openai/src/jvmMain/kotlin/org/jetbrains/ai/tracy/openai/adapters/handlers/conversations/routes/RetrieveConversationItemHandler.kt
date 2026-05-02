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
 * Handles GET /conversations/{id}/items/{item_id} — retrieves a specific conversation item.
 *
 * Sets `gen_ai.operation.name = "conversations.items.retrieve"` on the response span,
 * overriding the value written by `setCommonResponseAttributes`.
 *
 * Response attributes extracted:
 * - `body["id"]` → `tracy.conversation.item.id`
 * - `body["type"]` → `tracy.conversation.item.type`
 * - `body["status"]` → `tracy.conversation.item.status`
 */
internal class RetrieveConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No additional request attributes needed beyond what the parent handler sets
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.items.retrieve")
        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
        body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
    }
}
