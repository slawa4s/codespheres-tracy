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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Gemini `embedContent` and text-embedding `predict` (Vertex AI) requests and responses.
 *
 * Overrides `gen_ai.operation.name` to `"embedContent"` for both URL forms, sets
 * `gen_ai.output.type = "embedding"`, and extracts:
 * - `gen_ai.request.task_type` from `body.taskType`
 * - `gen_ai.request.output_dimensionality` from `body.outputDimensionality`
 * - `gen_ai.response.embedding.dimension` from `response.embedding.values.size`
 *
 * See [Embed Content API](https://ai.google.dev/api/embeddings)
 */
class GeminiEmbedHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "embedContent")
        span.setAttribute(GEN_AI_OUTPUT_TYPE, "embedding")

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

        body["embedding"]?.jsonObject?.get("values")?.jsonArray?.let { values ->
            span.setAttribute("gen_ai.response.embedding.dimension", values.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
