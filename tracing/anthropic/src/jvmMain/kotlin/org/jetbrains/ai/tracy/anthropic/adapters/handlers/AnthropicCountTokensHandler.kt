/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Count Tokens API (`/v1/messages/count_tokens`).
 *
 * Extracts telemetry attributes for token counting requests and responses.
 * The response body contains a top-level `input_tokens` field (not nested under `usage`).
 *
 * See: [Anthropic Count Tokens API](https://docs.anthropic.com/en/api/messages-count-tokens)
 */
internal class AnthropicCountTokensHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "count_tokens")
        span.setAttribute("anthropic.api.type", "count_tokens")
        span.setAttribute("gen_ai.provider.name", "anthropic")
        span.setAttribute("server.address", request.url.host)
        span.setAttribute("server.port", if (request.url.scheme == "https") 443L else 80L)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
