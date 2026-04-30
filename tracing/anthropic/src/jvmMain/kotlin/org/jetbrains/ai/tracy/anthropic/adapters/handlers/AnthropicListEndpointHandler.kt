/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * No-op handler for Anthropic list endpoints (batches, files, models).
 *
 * These read-only list routes do not carry LLM inference payloads, so no telemetry
 * attributes are extracted. The handler exists so that [AnthropicLLMTracingAdapter]
 * can route unknown/list URLs without falling back to the messages handler.
 *
 * See:
 * - [Messages Batches API](https://docs.anthropic.com/en/api/messages-batches)
 * - [Files API](https://docs.anthropic.com/en/api/files)
 * - [Models API](https://docs.anthropic.com/en/api/models)
 */
internal class AnthropicListEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) = Unit
    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) = Unit
    override fun handleStreaming(span: Span, events: String) = Unit
}
