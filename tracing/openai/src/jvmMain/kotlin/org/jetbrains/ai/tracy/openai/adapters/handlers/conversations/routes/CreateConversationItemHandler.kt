/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handles the `POST /conversations/{conversation_id}/items` endpoint — creates a new item in a conversation.
 */
internal class CreateConversationItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No special request attributes for item creation
    }

    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        // No special response attributes for item creation beyond common ones
    }
}
