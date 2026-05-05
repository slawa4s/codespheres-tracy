/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for the OpenAI Conversations API.
 *
 * Supported routes:
 * - `POST /v1/conversations` → `conversations.create`
 * - `GET /v1/conversations/{conversation_id}` → `conversations.retrieve`
 * - `POST /v1/conversations/{conversation_id}` → `conversations.update`
 * - `DELETE /v1/conversations/{conversation_id}` → `conversations.delete`
 *
 * `gen_ai.operation.name` is derived from the HTTP method and URL structure and overrides
 * the raw `object` field value (e.g. `"conversation"` or `"conversation.deleted"`) written
 * by [OpenAIApiUtils.setCommonResponseAttributes].
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        // Override the raw `object` field value set by setCommonResponseAttributes with a
        // semantically meaningful operation name derived from the HTTP method and URL.
        val operationName = deriveOperationName(response.url, response.requestMethod)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API does not use server-sent events streaming" }
    }

    /**
     * Derives the `gen_ai.operation.name` value from the HTTP method and URL path structure.
     *
     * The presence of a conversation-ID path segment (any non-blank segment immediately after
     * `"conversations"`) distinguishes create from update/retrieve/delete.
     */
    private fun deriveOperationName(url: TracyHttpUrl, method: String): String {
        val segments = url.pathSegments
        val conversationsIndex = segments.indexOf("conversations")
        if (conversationsIndex == -1) {
            logger.warn { "Failed to detect conversation route — no `conversations` segment in: ${segments.joinToString("/")}" }
            return "conversations.create"
        }

        val hasConversationId = segments.size > (conversationsIndex + 1) &&
                segments[conversationsIndex + 1].isNotBlank()

        return when {
            method == "POST" && !hasConversationId -> "conversations.create"
            method == "GET" && hasConversationId -> "conversations.retrieve"
            method == "POST" && hasConversationId -> "conversations.update"
            method == "DELETE" && hasConversationId -> "conversations.delete"
            else -> {
                logger.warn { "Failed to detect conversation route: $method ${segments.joinToString("/")}" }
                "conversations.create"
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
