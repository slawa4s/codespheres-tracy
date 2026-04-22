/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.*
import kotlinx.serialization.json.*

/**
 * Extracts request/response bodies of the OpenAI Embeddings API.
 *
 * See [Embeddings API](https://platform.openai.com/docs/api-reference/embeddings)
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Always set operation name explicitly — do not rely on response body["object"]
        // because that returns "list", which is incorrect for this operation.
        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        body["encoding_format"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute("gen_ai.request.encoding_format", it)
        }
        body["dimensions"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.request.dimensions", it)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        body["usage"]?.jsonObject?.let { usage ->
            usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
            usage["total_tokens"]?.jsonPrimitive?.longOrNull?.let {
                span.setAttribute("gen_ai.usage.total_tokens", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Embeddings API does not support streaming
    }
}
