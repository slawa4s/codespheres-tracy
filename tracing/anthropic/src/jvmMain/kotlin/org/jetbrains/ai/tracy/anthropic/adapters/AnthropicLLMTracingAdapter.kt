/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters

import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicBatchesEndpointHandler
import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicMessagesEndpointHandler
import org.jetbrains.ai.tracy.anthropic.adapters.handlers.AnthropicModelsEndpointHandler
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractorImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiSystemIncubatingValues
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Detects which Anthropic API endpoint is being called based on the request URL.
 *
 * Ordering matters: BATCHES must appear before MESSAGES because "messages/batches" contains
 * "messages". MODELS must appear before MESSAGES to prevent model IDs containing "messages"
 * from being misrouted to the messages handler.
 */
private enum class AnthropicApiType(val route: String) {
    // See: https://docs.anthropic.com/en/api/creating-message-batches
    // Must be checked before MESSAGES because "messages/batches" contains "messages".
    BATCHES("messages/batches"),

    // See: https://docs.anthropic.com/en/api/models
    // Must be checked before MESSAGES to avoid false matches on model IDs containing "messages".
    MODELS("models"),

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
 * Detects the target Anthropic API endpoint from the request URL and delegates attribute
 * extraction to the appropriate per-endpoint handler:
 * - [AnthropicMessagesEndpointHandler] for `POST /v1/messages`
 * - [AnthropicBatchesEndpointHandler] for `/v1/messages/batches` (create / retrieve / cancel)
 * - [AnthropicModelsEndpointHandler] for `/v1/models` (list) and `/v1/models/{id}` (retrieve)
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
 * See: [Anthropic API Reference](https://docs.anthropic.com/en/api/overview)
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

    override fun isStreamingRequest(request: TracyHttpRequest) =
        request.body.asJson()?.jsonObject?.get("stream")?.jsonPrimitive?.boolean ?: false

    override fun handleStreaming(span: Span, url: TracyHttpUrl, events: String) =
        handlerFor(url).handleStreaming(span, events)

    /**
     * Returns the [EndpointApiHandler] appropriate for the given URL, creating and caching
     * handler instances on first use.
     */
    private fun handlerFor(url: TracyHttpUrl): EndpointApiHandler {
        val apiType = AnthropicApiType.detect(url)
        return when (apiType) {
            AnthropicApiType.MESSAGES -> handlers.getOrPut(AnthropicApiType.MESSAGES) {
                AnthropicMessagesEndpointHandler(MediaContentExtractorImpl())
            }
            AnthropicApiType.BATCHES -> handlers.getOrPut(AnthropicApiType.BATCHES) {
                AnthropicBatchesEndpointHandler()
            }
            AnthropicApiType.MODELS -> handlers.getOrPut(AnthropicApiType.MODELS) {
                AnthropicModelsEndpointHandler()
            }
        }
    }
}
