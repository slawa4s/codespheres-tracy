/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicListEndpointHandler
import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesHandler
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
 * Detects which Anthropic API is being used based on the request URL.
 */
internal enum class AnthropicApiType {
    // See: https://docs.anthropic.com/en/api/messages
    MESSAGES,

    // See: https://docs.anthropic.com/en/api/messages-batches (batches),
    //      https://docs.anthropic.com/en/api/files (files),
    //      https://docs.anthropic.com/en/api/models (models)
    LIST;

    companion object {
        fun detect(url: TracyHttpUrl): AnthropicApiType {
            val segments = url.pathSegments
            return when {
                segments.contains("batches") ||
                segments.contains("files") ||
                segments.contains("models") -> LIST
                else -> MESSAGES
            }
        }
    }
}

/**
 * Tracing adapter for Anthropic Claude API.
 *
 * Automatically detects the Anthropic endpoint from the request URL and delegates to a
 * dedicated [EndpointApiHandler]:
 * - **Messages API** (`/v1/messages`): handled by [AnthropicMessagesHandler].
 * - **List routes** (batches, files, models): handled by [AnthropicListEndpointHandler].
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
        handlerFor(request.url).handleRequestAttributes(span, request)
    }

    override fun getResponseBodyAttributes(span: Span, response: TracyHttpResponse) {
        handlerFor(response.url).handleResponseAttributes(span, response)
    }

    override fun getSpanName(request: TracyHttpRequest) = "Anthropic-generation"

    // streaming is not supported
    override fun isStreamingRequest(request: TracyHttpRequest) = false
    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) {
        handlerFor(url).handleStreaming(span, events)
    }

    /**
     * Determines the appropriate handler for an Anthropic API endpoint based on the given URL.
     *
     * @param endpoint The URL used to detect the API type and determine the corresponding handler.
     * @return An instance of [EndpointApiHandler] that is capable of handling requests for the detected API type.
     */
    private fun handlerFor(endpoint: TracyHttpUrl): EndpointApiHandler {
        val apiType = AnthropicApiType.detect(endpoint)
        return when (apiType) {
            AnthropicApiType.MESSAGES -> handlers.getOrPut(AnthropicApiType.MESSAGES) {
                AnthropicMessagesHandler(MediaContentExtractorImpl())
            }
            AnthropicApiType.LIST -> handlers.getOrPut(AnthropicApiType.LIST) {
                AnthropicListEndpointHandler()
            }
        }
    }
}
