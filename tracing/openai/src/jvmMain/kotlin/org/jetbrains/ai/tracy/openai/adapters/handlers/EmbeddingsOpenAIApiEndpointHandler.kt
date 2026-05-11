/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import java.util.Base64

/**
 * Handler for OpenAI Embeddings API.
 *
 * See [Embeddings API](https://platform.openai.com/docs/api-reference/embeddings)
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }

        body["encoding_format"]?.jsonPrimitive?.contentOrNull?.let {
            span.setAttribute(AttributeKey.stringArrayKey("gen_ai.request.encoding_formats"), listOf(it))
        }
        body["dimensions"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.embeddings.dimension.count", it.toLong())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }

        body["usage"]?.jsonObject?.let { usage ->
            usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let { span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it) }
        }

        // dimensions from first embedding vector
        val data = body["data"]?.jsonArray
        if (data != null && data.isNotEmpty()) {
            val firstEmbedding = data.firstOrNull()?.jsonObject?.get("embedding")
            when {
                firstEmbedding is JsonArray ->
                    span.setAttribute("gen_ai.embeddings.dimension.count", firstEmbedding.size.toLong())
                firstEmbedding is JsonPrimitive -> {
                    // base64-encoded float32 array: bytes / 4 = dimension count
                    runCatching {
                        val bytes = Base64.getDecoder().decode(firstEmbedding.content)
                        span.setAttribute("gen_ai.embeddings.dimension.count", (bytes.size / 4).toLong())
                    }
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
