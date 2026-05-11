/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Moderations API (`POST /v1/moderations`).
 *
 * Sets `gen_ai.operation.name = "moderations"` and `openai.api.type = "moderations"`.
 *
 * **Request attributes:**
 * - `tracy.request.input.type` — `"string"` when `input` is a JSON string or an array of strings;
 *   `"multimodal"` when `input` is an array containing objects (e.g., image_url entries).
 *
 * **Response attributes (from the first element of `results`):**
 * - `tracy.response.results.count` — total number of result objects in the `results` array
 * - `tracy.response.results.flagged` — whether the first result was flagged
 * - `tracy.response.results.categories` — JSON-stringified `categories` map from the first result
 * - `tracy.response.results.category_scores` — JSON-stringified `category_scores` map from the first result
 * - `tracy.response.results.category_applied_input_types` — JSON-stringified `category_applied_input_types`
 *   map from the first result (omni-moderation models only, may be absent)
 *
 * `handleStreaming` is a no-op; the Moderations API does not support streaming.
 *
 * See [OpenAI Moderations API](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")
        span.setAttribute("openai.api.type", "moderations")

        val body = request.body.asJson()?.jsonObject ?: return

        val input = body["input"]
        val inputType = when (input) {
            is JsonPrimitive -> "string"
            is JsonArray -> {
                // "multimodal" if any element is a JsonObject; otherwise "string"
                if (input.jsonArray.any { it is JsonObject }) "multimodal" else "string"
            }
            else -> null
        }
        inputType?.let { span.setAttribute("tracy.request.input.type", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        val results = body["results"]?.jsonArray ?: return

        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val first = results.firstOrNull()?.jsonObject ?: return

        first["flagged"]?.jsonPrimitive?.boolean?.let {
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

    override fun handleStreaming(span: Span, events: String) = Unit
}
