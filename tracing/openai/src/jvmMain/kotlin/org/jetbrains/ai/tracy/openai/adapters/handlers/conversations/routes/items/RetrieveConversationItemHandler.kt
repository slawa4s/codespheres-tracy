/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.items

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes.extractConversationIdFromPath

/**
 * Handles the `GET /conversations/{conversation_id}/items/{item_id}` endpoint.
 */
internal class RetrieveConversationItemHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.conversation_id", it)
        }
        extractItemIdFromPath(request.url)?.let {
            span.setAttribute("tracy.request.item_id", it)
        }
        val include = request.url.parameters.queryParameterValues("include").filterNotNull()
        if (include.isNotEmpty()) {
            span.setAttribute("tracy.request.include", include.joinToString(","))
        }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceConversationItem(body)
    }
}
