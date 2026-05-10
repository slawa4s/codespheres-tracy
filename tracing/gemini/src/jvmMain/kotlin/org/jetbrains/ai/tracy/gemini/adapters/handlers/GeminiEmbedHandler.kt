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
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles Gemini embedding requests (embedContent / batchEmbedContents).
 *
 * Vertex AI sends these as "predict" requests; the adapter remaps the operation name
 * and output type before dispatching to this handler.
 */
internal class GeminiEmbedHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        // Vertex AI embed format: {"instances": [{"content": "..."}, ...]}
        val instances = body["instances"] as? JsonArray
        val taskType = instances?.firstOrNull()?.jsonObject?.get("task_type")?.jsonPrimitive?.content
        taskType?.let { span.setAttribute("gen_ai.request.embedding_task_type", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // Vertex AI embed response: {"predictions": [{"embeddings": {"values": [...], ...}}, ...]}
        val predictions = body["predictions"] as? JsonArray ?: return
        span.setAttribute("gen_ai.response.embedding.count", predictions.size.toLong())

        val firstEmbedding = predictions.firstOrNull()?.jsonObject
            ?.get("embeddings")?.jsonObject
            ?.get("values") as? JsonArray
        firstEmbedding?.let {
            span.setAttribute("gen_ai.response.embedding.dimension", it.size.toLong())
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
