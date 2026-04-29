/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handler for OpenAI Conversations API.
 *
 * Handles tracing for the Conversations API endpoints, with special support for the
 * list items route (`GET /conversations/{conversation_id}/items`) which carries
 * pagination query parameters.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        if (isListItemsRoute(request)) {
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // no additional response attributes for conversations endpoints
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use server-sent events streaming
    }

    /**
     * Returns true when the request targets the conversation items list endpoint:
     * `GET /conversations/{conversation_id}/items` (without a trailing item ID).
     */
    private fun isListItemsRoute(request: TracyHttpRequest): Boolean {
        if (request.method != "GET") return false
        val segments = request.url.pathSegments
        val itemsIndex = segments.indexOf("items")
        if (itemsIndex == -1) return false
        // items must be the last segment (no item ID following it)
        return itemsIndex == segments.lastIndex
    }
}
