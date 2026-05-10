/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Conversations API (`/v1/conversations/...`).
 *
 * Extracts the following span attributes:
 * - `openai.api.type` = `"conversations"` (OpenAI-specific)
 * - `gen_ai.request.model` — model name from the request body
 * - `gen_ai.conversation.id` — conversation identifier from the response `id` field
 * - `tracy.conversation.created_at` — Unix timestamp of conversation creation
 *
 * Common response attributes (`gen_ai.response.id`, `gen_ai.operation.name`,
 * `gen_ai.response.model`) are set by the adapter via [OpenAIApiUtils] before
 * this handler is invoked.
 *
 * See [OpenAI Conversations API](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    private val extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "conversations")
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.conversation.id", it)
        }
        body["created_at"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("tracy.conversation.created_at", it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.debug { "Conversations API does not use SSE streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
