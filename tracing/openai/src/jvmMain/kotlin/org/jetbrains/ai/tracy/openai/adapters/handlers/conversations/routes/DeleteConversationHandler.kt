/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations.routes

import io.opentelemetry.api.trace.Span
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
 * Handles [ConversationsOpenAIApiEndpointHandler.ConversationRoute.DELETE_CONVERSATION] endpoint:
 * `DELETE /conversations/{conversation_id}`.
 */
internal class DeleteConversationHandler : ConversationRouteHandler {
    override fun handleRequest(span: Span, request: TracyHttpRequest) {
        // No specific request attributes to extract for conversation deletion
    }

    /**
     * Response: Deletion confirmation containing deleted flag.
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

        body["deleted"]?.let {
            span.setAttribute("tracy.conversation.deleted", it.jsonPrimitive.boolean)
        }
    }
}
