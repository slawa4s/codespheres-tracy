/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.items

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractConversationIdFromPath

/**
 * Handles the `DELETE /conversations/{conversation_id}/items/{item_id}` endpoint.
 */
internal class DeleteConversationItemHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
        extractItemIdFromPath(response.url)?.let {
            span.setAttribute("tracy.conversation.item.id", it)
        }
    }
}
