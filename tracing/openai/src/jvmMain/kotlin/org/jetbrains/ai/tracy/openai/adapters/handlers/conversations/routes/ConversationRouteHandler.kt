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
 * Handles requests and responses for different conversation API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts the conversation ID from a path like `/v1/conversations/{conv_id}` or
 * `/v1/conversations/{conv_id}/items/{item_id}`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val convIndex = segments.indexOf("conversations")
    if (convIndex == -1 || segments.size <= convIndex + 1) return null
    val candidate = segments[convIndex + 1]
    return if (candidate.isNotBlank() && candidate != "conversations" && candidate != "items") candidate else null
}

/**
 * Extracts the item ID from a path like `/v1/conversations/{conv_id}/items/{item_id}`.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val itemsIndex = segments.indexOf("items")
    if (itemsIndex == -1 || segments.size <= itemsIndex + 1) return null
    val candidate = segments[itemsIndex + 1]
    return if (candidate.isNotBlank() && candidate != "items") candidate else null
}
