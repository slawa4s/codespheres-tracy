/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * OTel GenAI registered attribute for the unique identifier of a conversation (session/thread).
 * See: https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/
 */
internal val GEN_AI_CONVERSATION_ID: AttributeKey<String> =
    AttributeKey.stringKey("gen_ai.conversation.id")

/**
 * Handles requests and responses for different conversation API routes of OpenAI.
 */
internal interface ConversationRouteHandler {
    fun handleRequest(span: Span, request: TracyHttpRequest)
    fun handleResponse(span: Span, response: TracyHttpResponse)
}

/**
 * Extracts a conversation ID from a path like `/v1/conversations/{conversation_id}`.
 *
 * Returns the path segment immediately after `conversations` when it is non-blank and not `items`.
 * Returns `null` when no ID segment exists (e.g. `POST /conversations`) or when the segment is
 * `items` (guarding against a malformed route match).
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")

    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val potentialId = segments[conversationsIndex + 1]
        if (potentialId.isNotBlank() && potentialId != "items" && potentialId != "conversations") {
            potentialId
        } else {
            null
        }
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
