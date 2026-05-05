/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.ConversationsOpenAIApiEndpointHandler

private val logger = KotlinLogging.logger {}

/**
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.CREATE_ITEM] endpoint:
 * `POST /conversations/{conversation_id}/items`.
 */
internal class CreateItemHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No specific request attributes to extract for item creation
    }

    /**
     * Response: Paginated list of items containing data, first_id, last_id, has_more.
     * Also reads conversation ID from the URL path.
     */
    override fun handleResponse(span: Span, response: TracyHttpResponse) {
        val conversationId = extractConversationIdFromPath(response.url)
        if (conversationId != null) {
            span.setAttribute("gen_ai.conversation.id", conversationId)
        } else {
            logger.warn { "Failed to extract conversation ID from URL: ${response.url.pathSegments.joinToString("/")}" }
        }

        val body = response.body.asJson()?.jsonObject ?: return

        val data = body["data"]
        if (data is JsonArray) {
            span.setAttribute("tracy.conversation.items.count", data.size.toLong())
        }

        body["first_id"]?.let {
            span.setAttribute("tracy.conversation.items.first_id", it.jsonPrimitive.content)
        }

        body["last_id"]?.let {
            span.setAttribute("tracy.conversation.items.last_id", it.jsonPrimitive.content)
        }

        body["has_more"]?.let {
            span.setAttribute("tracy.conversation.items.has_more", it.jsonPrimitive.boolean)
        }
    }
}
