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
 * Handles requests and responses for a specific OpenAI Conversations API sub-route.
 *
 * Implementations are responsible for extracting and recording span attributes that are
 * unique to their route — common attributes (e.g. `gen_ai.response.id`, `gen_ai.operation.name`)
 * are already set by the outer [org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter].
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Returns the path segment immediately following `"conversations"`, i.e. the conversation ID.
 *
 * Works for paths like `/v1/conversations/{conversation_id}` and
 * `/v1/conversations/{conversation_id}/items/{item_id}`.
 * Returns `null` when the `"conversations"` segment is absent or is the final segment.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val idx = segments.indexOf("conversations")
    return if (idx != -1 && segments.size > idx + 1) {
        segments[idx + 1].takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/**
 * Returns the path segment immediately following `"items"`, i.e. the item ID.
 *
 * Works for paths like `/v1/conversations/{conversation_id}/items/{item_id}`.
 * Returns `null` when the `"items"` segment is absent or is the final segment.
 */
internal fun extractItemIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val idx = segments.indexOf("items")
    return if (idx != -1 && segments.size > idx + 1) {
        segments[idx + 1].takeIf { it.isNotBlank() }
    } else {
        null
    }
}
