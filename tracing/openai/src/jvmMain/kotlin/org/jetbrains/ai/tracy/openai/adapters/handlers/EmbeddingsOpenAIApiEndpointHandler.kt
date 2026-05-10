/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Handler for OpenAI Embeddings API.
 * See: https://platform.openai.com/docs/api-reference/embeddings
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        body["encoding_format"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.encoding_formats", "[\"$it\"]")
        }
        body["dimensions"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.embeddings.dimension.count", it.toLong())
        }

        span.populateUnmappedAttributes(body, mappedRequestAttributes, PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }

        body["usage"]?.jsonObject?.let { usage ->
            usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let {
                span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it.toLong())
            }
        }

        // Extract dimension count from first embedding
        body["data"]?.jsonArray?.firstOrNull()?.jsonObject?.let { first ->
            when (val embedding = first["embedding"]) {
                is JsonArray -> {
                    if (embedding.size > 0) {
                        span.setAttribute("gen_ai.embeddings.dimension.count", embedding.size.toLong())
                    }
                }
                is JsonPrimitive -> {
                    // base64-encoded float32 array: 4 bytes per dimension
                    embedding.contentOrNull?.let { b64 ->
                        runCatching {
                            val decoded = Base64.getDecoder().decode(b64)
                            val dimensions = decoded.size / 4
                            if (dimensions > 0) {
                                span.setAttribute("gen_ai.embeddings.dimension.count", dimensions.toLong())
                            }
                        }
                    }
                }
                else -> Unit
            }
        }

        span.populateUnmappedAttributes(body, mappedResponseAttributes, PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String) = Unit

    private val mappedRequestAttributes = listOf("model", "input", "encoding_format", "dimensions")
    private val mappedResponseAttributes = listOf("model", "usage", "data", "object")
}
