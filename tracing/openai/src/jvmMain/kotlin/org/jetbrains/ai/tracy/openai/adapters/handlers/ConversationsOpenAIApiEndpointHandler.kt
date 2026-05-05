/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handler for OpenAI Conversations API.
 *
 * Sets server address/port and provider metadata attributes on the span.
 *
 * See [Conversations API Reference](https://platform.openai.com/docs/api-reference/conversations)
 */
internal class ConversationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.provider.name", "openai")
        span.setAttribute("openai.api.type", "conversations")
        span.setAttribute("server.address", request.url.host)
        span.setAttribute("server.port", request.url.port.toLong())
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // No additional response attributes for the Conversations endpoint
    }

    override fun handleStreaming(span: Span, events: String) {
        // Conversations API streaming is not handled here
    }
}
