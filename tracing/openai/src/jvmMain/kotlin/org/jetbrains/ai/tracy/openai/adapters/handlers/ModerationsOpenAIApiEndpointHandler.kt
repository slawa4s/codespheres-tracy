/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.*
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Moderations API.
 *
 * See [Moderations API](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return

        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")
        span.setAttribute("openai.api.type", "moderations")

        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_REQUEST_MODEL, it) }

        val input = body["input"]
        val inputType = when {
            input is JsonPrimitive -> "string"
            input is JsonArray && input.all { it is JsonPrimitive } -> "string_array"
            input is JsonArray -> "multimodal"
            else -> null
        }
        inputType?.let { span.setAttribute("tracy.request.input.type", it) }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
        body["model"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }

        val results = body["results"]?.jsonArray ?: return
        span.setAttribute("tracy.response.results.count", results.size.toLong())

        val firstResult = results.firstOrNull()?.jsonObject ?: return
        firstResult["flagged"]?.jsonPrimitive?.booleanOrNull?.let {
            span.setAttribute("tracy.response.results.flagged", it.toString())
        }
        firstResult["categories"]?.jsonObject?.let {
            span.setAttribute("tracy.response.results.categories", it.toString())
        }
        firstResult["category_scores"]?.jsonObject?.let {
            span.setAttribute("tracy.response.results.category_scores", it.toString())
        }
        firstResult["category_applied_input_types"]?.jsonObject?.let {
            span.setAttribute("tracy.response.results.category_applied_input_types", it.toString())
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
