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
 * Handler for the Anthropic count_tokens endpoint (`POST /v1/messages/count_tokens`).
 *
 * The response body for this endpoint contains only `{"input_tokens": N}` — no `id` field.
 * The per-request correlation ID is supplied by Anthropic via the `x-request-id` response header,
 * which is mapped to `gen_ai.response.id`.
 *
 * See: [Anthropic Count Tokens API](https://docs.anthropic.com/en/api/messages-count-tokens)
 */
internal class AnthropicCountTokensHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gen_ai.provider.name", "anthropic")
        span.setAttribute("server.address", request.url.host)
        span.setAttribute("server.port", if (request.url.scheme == "https") 443L else 80L)
        span.setAttribute(GEN_AI_OPERATION_NAME, "count_tokens")
        span.setAttribute("anthropic.api.type", "count_tokens")

        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute("http.response.status_code", response.code.toLong())

        // The count_tokens response body has no id field; use the x-request-id header instead.
        response.headers["x-request-id"]?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }

        if (response.code >= 400) return

        val body = response.body.asJson()?.jsonObject ?: return
        body["input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
