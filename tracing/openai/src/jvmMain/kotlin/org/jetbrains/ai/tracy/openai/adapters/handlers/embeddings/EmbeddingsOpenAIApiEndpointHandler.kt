/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.embeddings

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Embeddings API.
 *
 * Supports:
 * - `POST /embeddings` — Create embeddings (`embeddings`)
 *
 * See [Embeddings API Reference](https://platform.openai.com/docs/api-reference/embeddings)
 */
internal class EmbeddingsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it.jsonPrimitive.content) }

        body["encoding_format"]?.let {
            span.setAttribute(
                AttributeKey.stringArrayKey("gen_ai.request.encoding_formats"),
                listOf(it.jsonPrimitive.content)
            )
        }

        body["dimensions"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.embeddings.dimension.count", it.toLong())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val data = body["data"]
        if (data is JsonArray && data.isNotEmpty()) {
            val embedding = data[0].jsonObject["embedding"]
            if (embedding is JsonArray) {
                span.setAttribute("gen_ai.embeddings.dimension.count", embedding.size.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Embeddings API does not use SSE streaming
    }
}
