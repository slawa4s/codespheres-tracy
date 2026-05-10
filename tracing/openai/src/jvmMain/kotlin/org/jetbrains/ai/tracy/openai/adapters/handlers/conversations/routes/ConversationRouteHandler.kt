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
 * Handles requests and responses for different Conversations item API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts the conversation ID from a path like `/v1/conversations/{conversation_id}/items`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val convIndex = segments.indexOf("conversations")
    return if (convIndex != -1 && segments.size > convIndex + 1) {
        val potential = segments[convIndex + 1]
        if (potential.isNotBlank() && potential != "conversations") potential else null
    } else {
        null
    }
}

/**
 * Extracts the item ID from a path like `/v1/conversations/{conversation_id}/items/{item_id}`.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    return if (itemsIndex != -1 && segments.size > itemsIndex + 1) {
        val potential = segments[itemsIndex + 1]
        if (potential.isNotBlank() && potential != "items") potential else null
    } else {
        null
    }
}
