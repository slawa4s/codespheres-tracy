/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicBatchApiEndpointHandler
import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesApiEndpointHandler
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects which Anthropic API is being used based on the request URL path.
 *
 * [BATCHES] must be listed before [MESSAGES] so that the more specific path
 * `messages/batches` is matched before the broader `messages` path.
 */
private enum class AnthropicApiType(val route: String) {
    // See: https://docs.anthropic.com/en/api/creating-message-batches
    BATCHES("messages/batches"),

    // See: https://docs.anthropic.com/en/api/messages
    MESSAGES("messages");

    companion object {
        fun detect(url: TracyHttpUrl): AnthropicApiType {
            val route = url.pathSegments.joinToString(separator = "/")
            return entries.firstOrNull { route.contains(it.route) } ?: MESSAGES
        }
    }
}

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Detects the Anthropic endpoint being called from the request URL and dispatches to the
 * appropriate per-endpoint handler:
 * - `/v1/messages` → [AnthropicMessagesApiEndpointHandler]
 * - `/v1/messages/batches` → [AnthropicBatchApiEndpointHandler]
 *
 * Sets `anthropic.api.type` on every span to identify the endpoint type (`"messages"` or `"batches"`).
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
    private val handlers = ConcurrentHashMap<AnthropicApiType, EndpointApiHandler>()

    override fun getRequestBodyAttributes(span: Span, request: TracyHttpRequest) {
        val apiType = AnthropicApiType.detect(request.url)
        span.setAttribute("anthropic.api.type", apiType.name.lowercase())
        handlerFor(request.url).handleRequestAttributes(span, request)
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        handlerFor(response.url).handleResponseAttributes(span, response)
    }

    override fun getSpanName(request: TracyHttpRequest): String = when (AnthropicApiType.detect(request.url)) {
        AnthropicApiType.BATCHES -> "Anthropic-batch"
        AnthropicApiType.MESSAGES -> "Anthropic-generation"
    }

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) = Unit

    /**
     * Returns the appropriate [EndpointApiHandler] for the given URL.
     *
     * Handlers are created on first use and cached for reuse.
     */
    private fun handlerFor(url: TracyHttpUrl): EndpointApiHandler {
        return when (AnthropicApiType.detect(url)) {
            AnthropicApiType.BATCHES -> handlers.getOrPut(AnthropicApiType.BATCHES) {
                AnthropicBatchApiEndpointHandler()
            }
            AnthropicApiType.MESSAGES -> handlers.getOrPut(AnthropicApiType.MESSAGES) {
                AnthropicMessagesApiEndpointHandler(MediaContentExtractorImpl())
            }
        }
    }
}
