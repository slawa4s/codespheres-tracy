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
 * The Conversations API response carries `"object": "conversation"`, which would otherwise be
 * recorded verbatim as `gen_ai.operation.name` by the shared `setCommonResponseAttributes`
 * helper.  This handler overrides that value with the semantically correct
 * `"conversations.create"` so that span attributes match OpenTelemetry GenAI conventions.
 *
 * See [OpenAI Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // setCommonResponseAttributes (called unconditionally by the adapter before this method)
        // copies the response "object" field ("conversation") into gen_ai.operation.name.
        // Override it with the correct operation name for the Conversations API.
        span.setAttribute(GEN_AI_OPERATION_NAME, "conversations.create")
    }

    override fun handleStreaming(span: Span, events: String) {
        // The Conversations API does not use server-sent events streaming.
    }
}
