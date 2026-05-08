/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OUTPUT_TYPE
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Parses Gemini Embed Content API requests and responses.
 *
 * Handles both the native Gemini format (`embedContent` operation) and the
 * Vertex AI predict format (`predict` operation with an embedding model), e.g., when
 * routed through a LiteLLM proxy as `gemini-embedding-001:predict`.
 *
 * ## Request attribute mapping
 *
 * | Source field                          | OTel attribute                       |
 * |---------------------------------------|--------------------------------------|
 * | `instances[0].task_type` (Vertex AI)  | `gen_ai.request.task_type`           |
 * | `taskType` (native)                   | `gen_ai.request.task_type`           |
 * | `parameters.outputDimensionality`     | `gen_ai.request.output_dimensionality` |
 * | `outputDimensionality` (native)       | `gen_ai.request.output_dimensionality` |
 *
 * ## Response attribute mapping
 *
 * | Source field                                       | OTel attribute                       |
 * |----------------------------------------------------|--------------------------------------|
 * | `predictions[0].embeddings.values.length` (Vertex) | `gen_ai.response.embedding.dimension` |
 * | `embedding.values.length` (native)                 | `gen_ai.response.embedding.dimension` |
 *
 * See: [Embed Content API](https://ai.google.dev/api/embeddings#v1beta.models.embedContent),
 * [Vertex AI embedContent](https://cloud.google.com/vertex-ai/generative-ai/docs/model-reference/text-embeddings-api)
 */
internal class GeminiEmbedHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute("gemini.api.type", "models")

        // task_type: Vertex AI predict layout stores it inside instances[0].task_type;
        // native embedContent layout stores it at the top level as taskType.
        val taskType =
            body["instances"]?.jsonArray?.firstOrNull()?.jsonObject?.get("task_type")?.jsonPrimitive?.content
                ?: body["taskType"]?.jsonPrimitive?.content
        taskType?.let { span.setAttribute("gen_ai.request.task_type", it) }

        // outputDimensionality: Vertex AI predict layout stores it in parameters.outputDimensionality;
        // native embedContent layout stores it at the top level.
        val outputDimensionality =
            body["parameters"]?.jsonObject?.get("outputDimensionality")?.jsonPrimitive?.intOrNull
                ?: body["outputDimensionality"]?.jsonPrimitive?.intOrNull
        outputDimensionality?.let { span.setAttribute("gen_ai.request.output_dimensionality", it.toLong()) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")

        // Embedding dimension: Vertex AI predict response embeds it at predictions[0].embeddings.values;
        // native embedContent response has it at embedding.values.
        val dimension =
            body["predictions"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("embeddings")?.jsonObject?.get("values")?.jsonArray?.size
                ?: body["embedding"]?.jsonObject?.get("values")?.jsonArray?.size
        dimension?.let { span.setAttribute("gen_ai.response.embedding.dimension", it.toLong()) }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
