/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.jsonObject
import org.jetbrains.ai.tracy.core.adapters.handlers.RouteHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the `GET /conversations/{conversation_id}/items` endpoint.
 */
internal class ListConversationItemsHandler : RouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        extractConversationIdFromPath(request.url)?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
        val params = request.url.parameters
        params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
        params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
        params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        span.traceConversationItemsList(body)
    }
}
