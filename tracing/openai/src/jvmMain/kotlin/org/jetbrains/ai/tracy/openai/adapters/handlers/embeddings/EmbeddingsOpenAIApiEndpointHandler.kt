/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.embeddings

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils
import java.util.Base64

/**
 * Handler for the OpenAI Embeddings API.
 *
 * Creates a vector representation of a given input that can be easily consumed by
 * machine learning models and algorithms.
 *
 * See [Embeddings API Reference](https://platform.openai.com/docs/api-reference/embeddings)
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "embeddings")
        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        val body = request.body.asJson()?.jsonObject ?: return
        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }
        // Default encoding format is "float" per OpenAI API spec
        val format = body["encoding_format"]?.jsonPrimitive?.content ?: "float"
        span.setAttribute(
            AttributeKey.stringArrayKey("gen_ai.request.encoding_formats"),
            listOf(format)
        )
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override the gen_ai.operation.name that setCommonResponseAttributes sets from
        // the response "object" field ("list"), restoring the correct value "embeddings".
        span.setAttribute(GEN_AI_OPERATION_NAME, "embeddings")

        val body = response.body.asJson()?.jsonObject ?: return
        body["usage"]?.jsonObject?.get("prompt_tokens")?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, it)
        }
        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }
        // Derive embedding dimension from the first data item's embedding field
        body["data"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("embedding")?.let { embedding ->
            when (embedding) {
                is JsonArray -> span.setAttribute(
                    "gen_ai.embeddings.dimension.count",
                    embedding.size.toLong()
                )
                is JsonPrimitive -> {
                    // Base64-encoded float32 vector: each dimension occupies 4 bytes
                    try {
                        val decoded = Base64.getDecoder().decode(embedding.content)
                        span.setAttribute(
                            "gen_ai.embeddings.dimension.count",
                            (decoded.size / 4).toLong()
                        )
                    } catch (_: IllegalArgumentException) {
                        logger.warn { "Failed to decode base64-encoded embedding vector" }
                    }
                }
                else -> {}
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Embeddings API does not use server-sent events streaming
        logger.warn { "Embeddings API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
