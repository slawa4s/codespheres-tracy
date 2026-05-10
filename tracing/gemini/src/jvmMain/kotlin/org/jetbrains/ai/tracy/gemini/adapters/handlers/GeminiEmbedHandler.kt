/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import kotlinx.serialization.json.*

/**
 * Handles Vertex AI embedding requests (`predict` operation with embedding model names)
 * and direct Gemini `embedContent`/`batchEmbedContents` requests.
 *
 * Sets the following span attributes:
 * - `gen_ai.operation.name = "embedContent"`
 * - `gen_ai.output.type = "embedding"`
 * - `gemini.api.type = "models"`
 * - `gen_ai.request.task_type` — from `instances[0].task_type` (Vertex AI) or top-level `taskType` (direct API)
 * - `gen_ai.request.output_dimensionality` — from `parameters.outputDimensionality` (Vertex AI) or top-level `outputDimensionality` (direct API)
 * - `gen_ai.response.embedding.dimension` — size of the embedding values array from the response
 *
 * See: [Embed Content API](https://ai.google.dev/api/embeddings),
 * [Vertex AI Text Embeddings](https://cloud.google.com/vertex-ai/generative-ai/docs/embeddings/get-text-embeddings)
 */
class GeminiEmbedHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "embedContent")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")
        span.setAttribute("gemini.api.type", "models")

        val body = request.body.asJson()?.jsonObject ?: return

        // Vertex AI format: instances[0].task_type and parameters.outputDimensionality
        val instances = body["instances"]?.jsonArray
        if (instances != null) {
            instances.firstOrNull()?.jsonObject?.get("task_type")?.jsonPrimitive?.contentOrNull
                ?.let { span.setAttribute("gen_ai.request.task_type", it) }
            body["parameters"]?.jsonObject?.get("outputDimensionality")?.jsonPrimitive?.intOrNull
                ?.let { span.setAttribute("gen_ai.request.output_dimensionality", it.toLong()) }
        } else {
            // Direct Gemini API format: top-level taskType and outputDimensionality
            body["taskType"]?.jsonPrimitive?.contentOrNull
                ?.let { span.setAttribute("gen_ai.request.task_type", it) }
            body["outputDimensionality"]?.jsonPrimitive?.intOrNull
                ?.let { span.setAttribute("gen_ai.request.output_dimensionality", it.toLong()) }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Vertex AI format: predictions[0].embeddings.values
        val predictions = body["predictions"]?.jsonArray
        if (predictions != null) {
            predictions.firstOrNull()?.jsonObject
                ?.get("embeddings")?.jsonObject
                ?.get("values")?.jsonArray
                ?.let { span.setAttribute("gen_ai.response.embedding.dimension", it.size.toLong()) }
        } else {
            // Direct Gemini API format: embedding.values
            body["embedding"]?.jsonObject
                ?.get("values")?.jsonArray
                ?.let { span.setAttribute("gen_ai.response.embedding.dimension", it.size.toLong()) }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
