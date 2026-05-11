/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.PayloadType
import org.jetbrains.ai.tracy.core.adapters.LLMTracingAdapter.Companion.populateUnmappedAttributes
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handler for OpenAI Moderations API.
 * See: https://platform.openai.com/docs/api-reference/moderations
 */
internal class ModerationsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val body = request.body.asJson()?.jsonObject ?: return
        OpenAIApiUtils.setCommonRequestAttributes(span, request)

        val input = body["input"]
        when {
            input is JsonPrimitive -> span.setAttribute("tracy.request.input.type", "string")
            input is JsonArray -> {
                val type = if (input.any { it is JsonObject }) "multimodal" else "string_array"
                span.setAttribute("tracy.request.input.type", type)
            }
        }

        span.populateUnmappedAttributes(body, listOf("input", "model"), PayloadType.REQUEST)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_ID, it) }
        body["model"]?.jsonPrimitive?.content?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }

        body["results"]?.let { results ->
            if (results is JsonArray) {
                span.setAttribute("tracy.response.results.count", results.size.toLong())
                results.firstOrNull()?.jsonObject?.let { first ->
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
            }
        }

        span.populateUnmappedAttributes(body, listOf("id", "model", "results"), PayloadType.RESPONSE)
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
