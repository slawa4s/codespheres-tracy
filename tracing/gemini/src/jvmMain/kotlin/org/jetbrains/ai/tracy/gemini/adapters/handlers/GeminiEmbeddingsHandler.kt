/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Parses Gemini Embeddings API requests and responses.
 *
 * Overrides [GEN_AI_OPERATION_NAME] to `"embedContent"` to normalise the Vertex AI `:predict`
 * alias so that all embedding spans carry a consistent operation name regardless of which endpoint
 * variant was used.
 *
 * See: [Embed Content API](https://ai.google.dev/api/embeddings)
 */
internal class GeminiEmbeddingsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Normalise operation name — Vertex AI uses ":predict" for the same endpoint
        span.setAttribute(GEN_AI_OPERATION_NAME, "embedContent")
        span.setAttribute("gen_ai.output.type", "embedding")
        span.setAttribute("gemini.api.type", "models")

        val body = request.body.asJson()?.jsonObject ?: return

        body["taskType"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.request.task_type", it)
        }
        body["outputDimensionality"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.request.output_dimensionality", it.toLong())
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["embedding"]?.jsonObject?.get("values")?.jsonArray?.size?.let {
            span.setAttribute("gen_ai.response.embedding.dimension", it.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
