/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Count Tokens API.
 *
 * Endpoint: `POST /v1/messages/count_tokens`
 *
 * Sets:
 * - `anthropic.api.type` = `"count_tokens"`
 * - `gen_ai.operation.name` = `"count_tokens"`
 * - `gen_ai.request.model` from request body `model` field
 *
 * Response attributes:
 * - `gen_ai.usage.input_tokens` ← top-level `input_tokens` field
 * - `gen_ai.response.id`        ← top-level `id` field (present on some proxies)
 *
 * See: [Count Tokens API](https://docs.anthropic.com/en/api/messages-count-tokens)
 */
internal class CountTokensAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "count_tokens")
        span.setAttribute(GEN_AI_OPERATION_NAME, "count_tokens")

        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_ID, it)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Count Tokens API does not use SSE streaming
    }
}
