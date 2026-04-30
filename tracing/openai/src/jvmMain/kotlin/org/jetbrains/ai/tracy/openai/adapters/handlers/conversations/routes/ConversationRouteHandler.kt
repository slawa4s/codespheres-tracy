/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Handles requests and responses for different Conversations API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}` or
 * `/v1/conversations/{conversation_id}/messages`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")

    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val potentialId = segments[conversationsIndex + 1]
        if (potentialId.isNotBlank() && potentialId != "conversations") {
            potentialId
        } else {
            null
        }
    } else {
        null
    }
}
