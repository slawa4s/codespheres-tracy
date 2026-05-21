/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
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
import org.jetbrains.ai.tracy.core.policy.orRedactedInput

/**
 * Handles the OpenAI Moderations API endpoint (`POST /moderations`).
 *
 * Request attributes:
 * - `gen_ai.operation.name = "moderations"`
 * - `gen_ai.request.model` from request body's `model`
 * - `tracy.request.input.type`: one of `"string"`, `"array_of_strings"`, `"multimodal"`
 * - `tracy.request.input` or `tracy.request.input.{i}[.{key}]` depending on shape
 *
 * Response attributes:
 * - `gen_ai.response.id`, `gen_ai.response.model`
 * - `tracy.response.id`
 * - `tracy.response.results.count`
 * - Per-result `tracy.response.results.{i}.{flagged,categories,category_scores,category_applied_input_types}`
 *
 * See [Moderations API Reference](https://developers.openai.com/api/reference/resources/moderations/methods/create)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_NAME)

        val body = request.body.asJson()?.jsonObject ?: return

        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_REQUEST_MODEL, it)
        }

        when (val input = body["input"]) {
            is JsonPrimitive -> {
                span.setAttribute("tracy.request.input.type", "string")
                span.setAttribute("tracy.request.input", input.content.orRedactedInput())
            }
            is JsonObject -> {
                span.setAttribute("tracy.request.input.type", "multimodal")
                for ((key, value) in input) {
                    span.setAttribute("tracy.request.input.$key", value.toString().orRedactedInput())
                }
            }
            is JsonArray -> {
                val allStrings = input.all { it is JsonPrimitive }
                span.setAttribute(
                    "tracy.request.input.type",
                    if (allStrings) "array_of_strings" else "multimodal"
                )
                for ((i, item) in input.withIndex()) {
                    when (item) {
                        is JsonPrimitive -> {
                            span.setAttribute("tracy.request.input.$i", item.content.orRedactedInput())
                        }
                        is JsonObject -> {
                            for ((key, value) in item) {
                                span.setAttribute(
                                    "tracy.request.input.$i.$key",
                                    value.toString().orRedactedInput()
                                )
                            }
                        }
                        else -> { /* skip nested arrays / nulls */ }
                    }
                }
            }
            else -> Unit
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_ID, it)
            span.setAttribute("tracy.response.id", it)
        }
        body["model"]?.jsonPrimitive?.content?.let {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, it)
        }

        val results = body["results"]
        if (results is JsonArray) {
            span.setAttribute("tracy.response.results.count", results.size.toLong())
            for ((i, result) in results.withIndex()) {
                val obj = result as? JsonObject ?: continue
                traceModeration(span, obj, prefix = "tracy.response.results.$i")
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Moderations endpoint does not support SSE streaming
    }

    /**
     * Traces a single `Moderation` object's documented fields under `{prefix}.{field}`.
     */
    private fun traceModeration(span: Span, body: JsonObject, prefix: String) {
        body["flagged"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("$prefix.flagged", it)
        }
        body["categories"]?.let {
            span.setAttribute("$prefix.categories", it.toString())
        }
        body["category_scores"]?.let {
            span.setAttribute("$prefix.category_scores", it.toString())
        }
        body["category_applied_input_types"]?.let {
            span.setAttribute("$prefix.category_applied_input_types", it.toString())
        }
    }

    companion object {
        private const val OPERATION_NAME = "moderations"
    }
}
