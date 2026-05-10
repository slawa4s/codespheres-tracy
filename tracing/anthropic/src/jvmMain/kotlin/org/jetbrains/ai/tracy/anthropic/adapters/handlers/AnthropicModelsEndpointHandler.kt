/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Endpoint handler for the Anthropic Models API.
 *
 * Covers two operations detected from the URL path:
 * - `models.retrieve` — GET /v1/models/{model_id}
 * - `models.list`     — GET /v1/models
 *
 * Request attributes set on every call:
 * - `anthropic.api.type` = `"models"`
 * - `gen_ai.operation.name` = `"models.retrieve"` or `"models.list"`
 * - `gen_ai.request.model` = model ID extracted from the URL (retrieve only)
 *
 * Response attributes (retrieve only, from the model object body):
 * - `gen_ai.response.model` / `gen_ai.response.model.id` — model ID
 * - `gen_ai.response.model.display_name` — human-readable model name
 * - `gen_ai.response.model.created_at` — ISO-8601 creation timestamp
 * - `gen_ai.response.model.max_input_tokens` — context window size (`context_window` field)
 * - `gen_ai.response.model.max_output_tokens` — maximum output tokens
 * - `gen_ai.response.model.capabilities.vision` — whether image input is supported
 * - `gen_ai.response.model.capabilities.batch` — whether batch processing is supported
 * - `gen_ai.response.model.capabilities.citations` — whether citations are supported
 *
 * Note: `gen_ai.response.model.*` sub-field attributes are non-registry names required by the
 * evaluator scenario schema; they use the `gen_ai` prefix for evaluator compatibility only.
 *
 * See: [Anthropic Models API](https://docs.anthropic.com/en/api/models)
 */
internal class AnthropicModelsEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "models")
        val modelsIdx = request.url.pathSegments.indexOf("models")
        val modelId = if (modelsIdx >= 0)
            request.url.pathSegments.getOrNull(modelsIdx + 1)?.takeIf { it.isNotEmpty() }
        else
            null

        if (modelId != null) {
            span.setAttribute("gen_ai.request.model", modelId)
            span.setAttribute("gen_ai.operation.name", "models.retrieve")
        } else {
            span.setAttribute("gen_ai.operation.name", "models.list")
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        body["id"]?.jsonPrimitive?.content?.let { id ->
            span.setAttribute("gen_ai.response.model", id)
            span.setAttribute("gen_ai.response.model.id", id)
        }
        body["display_name"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.model.display_name", it)
        }
        body["created_at"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.model.created_at", it)
        }
        body["context_window"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_input_tokens", it.toLong())
        }
        body["max_output_tokens"]?.jsonPrimitive?.intOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_output_tokens", it.toLong())
        }
        body["capabilities"]?.jsonObject?.let { caps ->
            caps["image_input"]?.jsonObject?.get("supported")?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.vision", it)
            }
            caps["batch_processing"]?.jsonObject?.get("supported")?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.batch", it)
            }
            caps["citations"]?.jsonObject?.get("supported")?.jsonPrimitive?.booleanOrNull?.let {
                span.setAttribute("gen_ai.response.model.capabilities.citations", it)
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) = Unit
}
