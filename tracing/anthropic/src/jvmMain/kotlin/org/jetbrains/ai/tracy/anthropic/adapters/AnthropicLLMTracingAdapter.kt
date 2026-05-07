/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesApiEndpointHandler
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Delegates request and response attribute extraction to endpoint-specific handlers.
 * Currently supports the Messages API via [AnthropicMessagesApiEndpointHandler].
 * Additional Anthropic endpoints (e.g., Files, Models) can be added by extending
 * [handlerFor] with the appropriate handler.
 *
 * ## Example Usage
 * ```kotlin
 * val client = instrument(HttpClient(), AnthropicLLMTracingAdapter())
 * client.post("https://api.anthropic.com/v1/messages") {
 *     header("x-api-key", apiKey)
 *     header("anthropic-version", "2023-06-01")
 *     setBody("""
 *         {
 *             "max_tokens": 1024,
 *             "messages": [{"content": "Hello!", "role": "user"}],
 *             "model": "claude-3-7-sonnet-latest"
 *         }
 *     """)
 * }
 * // Automatically traces request/response with tool calls and media content
 * ```
 *
 * See: [Anthropic Messages API](https://docs.claude.com/en/api/messages)
 */
class AnthropicLLMTracingAdapter : LLMTracingAdapter(genAISystem = GenAiSystemIncubatingValues.ANTHROPIC) {
    private val messagesHandler: EndpointApiHandler =
        AnthropicMessagesApiEndpointHandler(MediaContentExtractorImpl())

    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        handlerFor(request.url).handleRequestAttributes(span, request)
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        handlerFor(response.url).handleResponseAttributes(span, response)
    }

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit

    /**
     * Returns the appropriate [EndpointApiHandler] for the given URL.
     *
     * Currently all Anthropic requests are routed to [AnthropicMessagesApiEndpointHandler].
     * Future Anthropic endpoints (e.g., Files, Models) can be added here.
     */
    private fun handlerFor(@Suppress("UNUSED_PARAMETER") url: TracyHttpUrl): EndpointApiHandler = messagesHandler
}
