/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the items.retrieve endpoint: `GET /conversations/{conversation_id}/items/{item_id}`.
 *
 * Response body:
 * - `id` → `tracy.conversation.item.id`
 * - `type` → `tracy.conversation.item.type`
 * - `status` → `tracy.conversation.item.status`
 */
internal class ItemsRetrieveHandler : ConversationItemRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No specific request attributes for retrieve
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let { span.setAttribute("tracy.conversation.item.id", it.jsonPrimitive.content) }
        body["type"]?.let { span.setAttribute("tracy.conversation.item.type", it.jsonPrimitive.content) }
        body["status"]?.let { span.setAttribute("tracy.conversation.item.status", it.jsonPrimitive.content) }
    }
}
