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
 * Handles requests and responses for a specific Conversations API route.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts the conversation ID from a path like `/v1/conversations/{conversation_id}` or
 * `/v1/conversations/{conversation_id}/items`.
 */
internal fun extractConversationId(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val idx = segments.indexOf("conversations")
    if (idx == -1 || segments.size <= idx + 1) return null
    val candidate = segments[idx + 1]
    return candidate.takeIf { it.isNotBlank() && it != "items" }
}

/**
 * Extracts the item ID from a path like `/v1/conversations/{conversation_id}/items/{item_id}`.
 */
internal fun extractItemId(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIdx = segments.indexOf("items")
    if (itemsIdx == -1 || segments.size <= itemsIdx + 1) return null
    val candidate = segments[itemsIdx + 1]
    return candidate.takeIf { it.isNotBlank() }
}
