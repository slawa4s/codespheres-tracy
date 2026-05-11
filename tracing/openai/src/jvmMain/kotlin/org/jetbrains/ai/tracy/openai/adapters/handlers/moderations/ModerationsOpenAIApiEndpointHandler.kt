/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handles the OpenAI Moderations API (`POST /v1/moderations`).
 *
 * Sets `openai.api.type = "moderations"` and `gen_ai.operation.name = "moderations"` on every span.
 *
 * Request attributes:
 * - `tracy.request.input.type`: `"string"` when the `input` field is a plain string,
 *   `"array"` when it is a JSON array.
 *
 * Response attributes (from the first element of the `results` array):
 * - `tracy.response.results.count`: total number of results
 * - `tracy.response.results.flagged`: whether the first result was flagged
 * - `tracy.response.results.categories`: JSON object of category flags
 * - `tracy.response.results.category_scores`: JSON object of category scores
 *
 * See [OpenAI Moderations API Reference](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        val body = request.body.asJson()?.jsonObject ?: return
        when (body["input"]) {
            is JsonPrimitive -> span.setAttribute("tracy.request.input.type", "string")
            is JsonArray -> span.setAttribute("tracy.request.input.type", "array")
            else -> {}
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val results = body["results"]?.jsonArray ?: return

        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val first = results.firstOrNull()?.jsonObject ?: return
        first["flagged"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.results.flagged", it)
        }
        first["categories"]?.let {
            span.setAttribute("tracy.response.results.categories", it.toString())
        }
        first["category_scores"]?.let {
            span.setAttribute("tracy.response.results.category_scores", it.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Moderations API does not use SSE streaming
    }
}
