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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Embeddings API.
 *
 * See: [Embeddings API](https://platform.openai.com/docs/api-reference/embeddings)
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "embeddings")
        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        // encoding_format may be a string or array; normalise to array form
        body["encoding_format"]?.let { fmt ->
            val formats = try {
                fmt.jsonArray.map { it.jsonPrimitive.content }
            } catch (_: Exception) {
                listOf(fmt.jsonPrimitive.content)
            }
            span.setAttribute("gen_ai.request.encoding_formats", formats.toString())
        }

        body["dimensions"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.request.dimensions", it.toLong())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "embeddings")
        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }

        body["data"]?.jsonArray?.let { data ->
            span.setAttribute("gen_ai.embeddings.dimension.count", data.size.toLong())
            data.firstOrNull()?.jsonObject?.let { first ->
                first["embedding"]?.jsonArray?.let { emb ->
                    span.setAttribute("gen_ai.response.embedding.dimension", emb.size.toLong())
                    span.setAttribute("gen_ai.response.embedding.count", data.size.toLong())
                }
            }
        }

        body["usage"]?.jsonObject?.let { usage ->
            usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
