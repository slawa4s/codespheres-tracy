/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME

/**
 * Handler for OpenAI Conversations API.
 *
 * The Conversations API is a stateful chat interface. Its response `object` field carries
 * resource-type strings (`"conversation"`, `"list"`, `"conversation.deleted"`) rather than
 * meaningful operation names, so this handler explicitly overwrites whatever
 * [OpenAIApiUtils.setCommonResponseAttributes] copies from `response.body["object"]` with
 * the semantically correct OpenTelemetry operation name `"chat"`.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setCommonResponseAttributes(span, response)

        // setCommonResponseAttributes blindly copies response["object"] to GEN_AI_OPERATION_NAME,
        // yielding incorrect values such as "conversation", "list", or "conversation.deleted".
        // Override with the semantically correct operation name.
        span.setAttribute(GEN_AI_OPERATION_NAME, "chat")
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API does not use SSE streaming
    }
}
