/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Models API.
 *
 * Supports:
 * - `GET /models` — List models (`models.list`)
 * - `GET /models/{model_id}` — Retrieve a model (`models.retrieve`)
 *
 * See [Models API Reference](https://docs.anthropic.com/en/api/models-list)
 */
internal class ModelsAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val modelsIndex = segments.indexOf("models")
        if (modelsIndex != -1 && segments.size > modelsIndex + 1 && segments[modelsIndex + 1].isNotBlank()) {
            val modelId = segments[modelsIndex + 1]
            span.setAttribute(GEN_AI_OPERATION_NAME, "models.retrieve")
            span.setAttribute(GEN_AI_REQUEST_MODEL, modelId)
        } else {
            span.setAttribute(GEN_AI_OPERATION_NAME, "models.list")
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.let {
            val id = it.jsonPrimitive.content
            span.setAttribute(GEN_AI_RESPONSE_MODEL, id)
            span.setAttribute("gen_ai.response.model.id", id)
        }
        body["display_name"]?.let {
            span.setAttribute("gen_ai.response.model.display_name", it.jsonPrimitive.content)
        }
        body["created_at"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.created_at", it.toLong())
        }
        body["max_input_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_input_tokens", it.toLong())
        }
        body["max_output_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_output_tokens", it.toLong())
        }

        body["capabilities"]?.jsonObject?.let { capabilities ->
            capabilities["batch"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.batch", it)
            }
            capabilities["citations"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.citations", it)
            }
            capabilities["vision"]?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.vision", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use SSE streaming
    }
}
