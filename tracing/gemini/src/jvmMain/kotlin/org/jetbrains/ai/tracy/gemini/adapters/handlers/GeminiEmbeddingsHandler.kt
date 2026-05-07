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
 * Derives the [GEN_AI_OPERATION_NAME] from the request URL's last path segment so that both
 * `embedContent` and `batchEmbedContents` operations are reported correctly. The Vertex AI
 * `:predict` alias is normalised to `"embedContent"`.
 *
 * Handles both single-embed responses (`body["embedding"]`) and batch responses
 * (`body["embeddings"]`), setting `gen_ai.response.embedding.count` and
 * `gen_ai.response.embedding.dimension` accordingly.
 *
 * See: [Embed Content API](https://ai.google.dev/api/embeddings)
 */
internal class GeminiEmbeddingsHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        // Derive operation name from the URL's last path segment (e.g. "model:batchEmbedContents")
        // and normalise Vertex AI's ":predict" alias to "embedContent".
        val lastSegment = request.url.pathSegments.lastOrNull() ?: ""
        val urlOperation = lastSegment.split(":").lastOrNull() ?: ""
        val operationName = if (urlOperation == "predict") "embedContent" else urlOperation
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

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

        // Single-embed response shape: body["embedding"]["values"]
        body["embedding"]?.jsonObject?.get("values")?.jsonArray?.size?.let {
            span.setAttribute("gen_ai.response.embedding.dimension", it.toLong())
        }

        // Batch-embed response shape: body["embeddings"][*]["values"]
        body["embeddings"]?.jsonArray?.let { embeddings ->
            span.setAttribute("gen_ai.response.embedding.count", embeddings.size.toLong())
            embeddings.firstOrNull()?.jsonObject?.get("values")?.jsonArray?.size?.let {
                span.setAttribute("gen_ai.response.embedding.dimension", it.toLong())
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
