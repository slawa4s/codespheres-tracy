/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import kotlinx.serialization.json.*

/**
 * Handles Gemini embedding API requests and responses, covering both the native
 * `embedContent` endpoint and the Vertex AI `predict` endpoint for embedding models.
 *
 * Sets the following span attributes:
 * - `gemini.api.type` = `"models"` (provider-specific)
 * - `gen_ai.operation.name` = `"embedContent"` (canonical regardless of transport)
 * - `gen_ai.output.type` = `"embedding"`
 * - `gen_ai.request.task_type` — from `taskType` (native) or `instances[0].task_type` (Vertex AI)
 * - `gen_ai.request.output_dimensionality` — from `outputDimensionality` (native) or
 *   `instances[0].outputDimensionality` / `parameters.outputDimensionality` (Vertex AI)
 * - `gen_ai.response.embedding.dimension` — length of the embedding values array
 *
 * See: [Gemini Embed API](https://ai.google.dev/api/embeddings)
 */
class GeminiEmbedHandler(
    @Suppress("UNUSED_PARAMETER") extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("gemini.api.type", "models")
        span.setAttribute(GEN_AI_OPERATION_NAME, "embedContent")

        val body = request.body.asJson()?.jsonObject ?: return

        // Native embedContent API: taskType and outputDimensionality are top-level fields
        val taskType = body["taskType"]?.jsonPrimitive?.contentOrNull
            ?: body["task_type"]?.jsonPrimitive?.contentOrNull
            // Vertex AI predict API: fields are inside instances[0]
            ?: body["instances"]?.jsonArray?.firstOrNull()?.jsonObject?.let { instance ->
                instance["taskType"]?.jsonPrimitive?.contentOrNull
                    ?: instance["task_type"]?.jsonPrimitive?.contentOrNull
            }

        taskType?.let { span.setAttribute("gen_ai.request.task_type", it) }

        val outputDimensionality = body["outputDimensionality"]?.jsonPrimitive?.intOrNull
            ?: body["output_dimensionality"]?.jsonPrimitive?.intOrNull
            // Vertex AI: check instances[0] fields
            ?: body["instances"]?.jsonArray?.firstOrNull()?.jsonObject?.let { instance ->
                instance["outputDimensionality"]?.jsonPrimitive?.intOrNull
                    ?: instance["output_dimensionality"]?.jsonPrimitive?.intOrNull
            }
            // Vertex AI: check parameters block
            ?: body["parameters"]?.jsonObject?.let { params ->
                params["outputDimensionality"]?.jsonPrimitive?.intOrNull
                    ?: params["output_dimensionality"]?.jsonPrimitive?.intOrNull
            }

        outputDimensionality?.let { span.setAttribute("gen_ai.request.output_dimensionality", it.toLong()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")

        val body = response.body.asJson()?.jsonObject ?: return

        // Native embedContent response: { "embedding": { "values": [...] } }
        val dimension = body["embedding"]?.jsonObject?.get("values")?.jsonArray?.size
            // Vertex AI predict response: { "predictions": [{ "embeddings": { "values": [...] } }] }
            ?: body["predictions"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("embeddings")?.jsonObject?.get("values")?.jsonArray?.size

        dimension?.let { span.setAttribute("gen_ai.response.embedding.dimension", it.toLong()) }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
