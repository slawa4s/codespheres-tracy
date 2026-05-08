/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handles the OpenAI Moderations API endpoint (`POST /v1/moderations`).
 *
 * Extracts span attributes from moderation requests and responses, including:
 * - `gen_ai.operation.name = "moderations"`
 * - `tracy.request.input.type`: `"string"` when input is a plain string, `"multimodal"` when it is an array
 * - `tracy.response.results.count`: number of results returned
 * - From `results[0]`: flagged status, categories, category scores, and applied input types
 *
 * See [Moderations API Reference](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }

        when (val input = body["input"]) {
            is JsonPrimitive -> span.setAttribute("tracy.request.input.type", "string")
            is JsonArray -> span.setAttribute("tracy.request.input.type", "multimodal")
            else -> Unit
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        val results = body["results"] as? JsonArray ?: return
        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val first = results.firstOrNull() as? JsonObject ?: return

        first["flagged"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.results.flagged", it)
        }

        first["categories"]?.let {
            span.setAttribute("tracy.response.results.categories", it.toString())
        }

        first["category_scores"]?.let {
            span.setAttribute("tracy.response.results.category_scores", it.toString())
        }

        first["category_applied_input_types"]?.let {
            span.setAttribute("tracy.response.results.category_applied_input_types", it.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Moderations endpoint does not support SSE streaming
    }

    companion object {
        private const val OPERATION_NAME = "moderations"
    }
}
