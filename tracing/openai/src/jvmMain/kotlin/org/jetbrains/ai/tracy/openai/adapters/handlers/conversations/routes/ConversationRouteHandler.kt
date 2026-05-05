/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
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

internal val OPENAI_API_TYPE: AttributeKey<String> = AttributeKey.stringKey("openai.api.type")
internal const val CONVERSATIONS_API_TYPE = "conversations"

/**
 * Sets [GEN_AI_OPERATION_NAME] and [OPENAI_API_TYPE] on the span from the request context.
 *
 * Must be called at request time so the operation name is derived from the HTTP method and
 * path pattern rather than from the response body's generic "object" field.
 */
internal fun Span.setConversationOperationAttributes(operationName: String) {
    setAttribute(GEN_AI_OPERATION_NAME, operationName)
    setAttribute(OPENAI_API_TYPE, CONVERSATIONS_API_TYPE)
}

/**
 * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}`.
 */
internal fun extractConversationIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val conversationsIndex = segments.indexOf("conversations")

    return if (conversationsIndex != -1 && segments.size > conversationsIndex + 1) {
        val potentialId = segments[conversationsIndex + 1]
        if (potentialId.isNotBlank()) potentialId else null
    } else {
        null
    }
}
