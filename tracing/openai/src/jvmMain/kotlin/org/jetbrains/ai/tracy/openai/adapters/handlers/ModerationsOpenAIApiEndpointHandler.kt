/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Moderations API.
 *
 * See: [Moderations API](https://platform.openai.com/docs/api-reference/moderations)
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {
    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        val body = request.body.asJson()?.jsonObject ?: return
        body["input"]?.let { input ->
            val inputType = when (input) {
                is kotlinx.serialization.json.JsonPrimitive -> "string"
                is JsonArray -> if (input.all { it is kotlinx.serialization.json.JsonPrimitive }) "string_array" else "multimodal"
                else -> "multimodal"
            }
            span.setAttribute("tracy.request.input.type", inputType)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonResponseAttributes(span, response)
        span.setAttribute("openai.api.type", "moderations")
        span.setAttribute(GEN_AI_OPERATION_NAME, "moderations")

        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }

        body["results"]?.let { results ->
            if (results is JsonArray) {
                span.setAttribute("tracy.response.results.count", results.size.toLong())
                results.firstOrNull()?.jsonObject?.let { first ->
                    first["flagged"]?.jsonPrimitive?.booleanOrNull?.let {
                        span.setAttribute("tracy.response.results.flagged", it)
                    }
                    first["categories"]?.jsonObject?.let {
                        span.setAttribute("tracy.response.results.categories", it.toString())
                    }
                    first["category_scores"]?.jsonObject?.let {
                        span.setAttribute("tracy.response.results.category_scores", it.toString())
                    }
                    first["category_applied_input_types"]?.jsonObject?.let {
                        span.setAttribute("tracy.response.results.category_applied_input_types", it.toString())
                    }
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
