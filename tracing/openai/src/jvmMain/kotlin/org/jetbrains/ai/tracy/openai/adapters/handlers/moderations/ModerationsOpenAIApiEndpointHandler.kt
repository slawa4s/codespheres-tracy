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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for the OpenAI Moderations API endpoint (`POST /v1/moderations`).
 *
 * Sets `gen_ai.operation.name=moderations` and `openai.api.type=moderations` on every span.
 *
 * ## Request attributes extracted
 * - `openai.api.type` — always `"moderations"`
 * - `gen_ai.operation.name` — always `"moderations"`
 * - `tracy.request.input.type` — `"string"` when `input` is a plain string or array of strings;
 *   `"multimodal"` when `input` is an array of objects each containing a `"type"` field
 *
 * ## Response attributes extracted
 * - `tracy.response.results.count` — size of the `results` array
 * - `tracy.response.results.flagged` — `results[0]["flagged"]` as a string
 * - `tracy.response.results.categories` — `results[0]["categories"]` as a JSON string
 * - `tracy.response.results.category_scores` — `results[0]["category_scores"]` as a JSON string
 * - `tracy.response.results.category_applied_input_types` — `results[0]["category_applied_input_types"]`
 *   as a JSON string (only present in multimodal responses)
 *
 * See [Moderations API Reference](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        val body = request.body.asJson()?.jsonObject ?: return
        val input = body["input"] ?: return

        val inputType = when {
            input is JsonPrimitive -> "string"
            input is JsonArray && input.isEmpty() -> "string"
            input is JsonArray && input[0] is JsonPrimitive -> "string"
            input is JsonArray && input[0] is JsonObject -> "multimodal"
            else -> null
        }
        inputType?.let { span.setAttribute("tracy.request.input.type", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Re-set because setCommonResponseAttributes may overwrite these
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        val body = response.body.asJson()?.jsonObject ?: return
        val results = body["results"]?.jsonArray ?: return
        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val first = results.firstOrNull()?.jsonObject ?: return
        first["flagged"]?.let { span.setAttribute("tracy.response.results.flagged", it.toString()) }
        first["categories"]?.let { span.setAttribute("tracy.response.results.categories", it.toString()) }
        first["category_scores"]?.let { span.setAttribute("tracy.response.results.category_scores", it.toString()) }
        first["category_applied_input_types"]?.let {
            span.setAttribute("tracy.response.results.category_applied_input_types", it.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Moderations API does not use server-sent events streaming" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
