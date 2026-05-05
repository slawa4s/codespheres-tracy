/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * Extracts `conversation_id` from a path like `/v1/conversations/{conversation_id}`.
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

/**
 * Traces a Conversation model object with its fields.
 *
 * Conversation schema:
 * - id: string
 * - object: string
 * - model: string
 * - created_at: number
 * - metadata: object
 * - status: string
 */
internal fun Span.traceConversationModel(conversation: JsonObject, prefix: String) {
    val span = this

    conversation["id"]?.let {
        span.setAttribute("$prefix.id", it.jsonPrimitive.content)
    }

    conversation["object"]?.let {
        span.setAttribute("$prefix.object", it.jsonPrimitive.content)
    }

    conversation["model"]?.let {
        span.setAttribute("$prefix.model", it.jsonPrimitive.content)
    }

    conversation["status"]?.let {
        span.setAttribute("$prefix.status", it.jsonPrimitive.content)
    }

    conversation["created_at"]?.jsonPrimitive?.longOrNull?.let {
        span.setAttribute("$prefix.created_at", it)
    }
}
