/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Handler for OpenAI Conversations API.
 *
 * Routes handled:
 * - `POST /conversations` - Create a conversation
 * - `GET /conversations` - List conversations (supports `limit`, `order`, `after` query params)
 * - `GET /conversations/{conversation_id}` - Get a specific conversation
 * - `DELETE /conversations/{conversation_id}` - Delete a conversation
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val conversationId = extractConversationIdFromPath(request.url)

        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            // list-type request — no specific conversation ID in path
            val params = request.url.parameters
            params.queryParameter("limit")?.let { span.setAttribute("tracy.request.limit", it) }
            params.queryParameter("order")?.let { span.setAttribute("tracy.request.order", it) }
            params.queryParameter("after")?.let { span.setAttribute("tracy.request.after", it) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // no additional response attributes for conversations
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
    }

    /**
     * Extracts the conversation ID from a path like `/v1/conversations/{conversation_id}`.
     * Returns null when no conversation ID is present (e.g. list endpoint).
     */
    private fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1 || segments.size <= conversationsIndex + 1) return null
        val potentialId = segments[conversationsIndex + 1]
        return if (potentialId.isNotBlank()) potentialId else null
    }
}
