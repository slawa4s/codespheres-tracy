/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Models API.
 *
 * Maps HTTP method + URL path to `gen_ai.operation.name`:
 * - `GET /v1/models`              → `"list"`
 * - `GET /v1/models/{model_id}`   → `"retrieve"`
 *
 * For "retrieve" operations, maps model response fields to OTel attributes:
 * - `id`               → `gen_ai.response.model` and `gen_ai.response.model.id`
 * - `display_name`     → `gen_ai.response.model.display_name`
 * - `created_at`       → `gen_ai.response.model.created_at`
 * - `max_input_tokens` → `gen_ai.response.model.max_input_tokens`
 * - `max_tokens`       → `gen_ai.response.model.max_output_tokens`
 * - `capabilities.*`   → `gen_ai.response.model.capabilities.{key}` (using `supported` field)
 *
 * See: [Models API](https://platform.claude.com/docs/en/api/models)
 */
internal class ModelsAnthropicApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "models")

        val operation = detectOperation(request.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operation)

        if (operation == "retrieve") {
            val modelId = modelIdFromUrl(request.url)
            if (modelId != null) {
                span.setAttribute(GEN_AI_REQUEST_MODEL, modelId)
            }
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val body = response.body.asJson()?.jsonObject ?: return

        // id → gen_ai.response.model + gen_ai.response.model.id
        body["id"]?.jsonPrimitive?.content?.let { id ->
            span.setAttribute(GEN_AI_RESPONSE_MODEL, id)
            span.setAttribute("gen_ai.response.model.id", id)
        }

        // display_name → gen_ai.response.model.display_name
        body["display_name"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.model.display_name", it)
        }

        // created_at → gen_ai.response.model.created_at
        body["created_at"]?.jsonPrimitive?.content?.let {
            span.setAttribute("gen_ai.response.model.created_at", it)
        }

        // max_input_tokens → gen_ai.response.model.max_input_tokens
        body["max_input_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_input_tokens", it)
        }

        // max_tokens (max output tokens per the API) → gen_ai.response.model.max_output_tokens
        body["max_tokens"]?.jsonPrimitive?.longOrNull?.let {
            span.setAttribute("gen_ai.response.model.max_output_tokens", it)
        }

        // capabilities: iterate top-level entries, emit gen_ai.response.model.capabilities.{key}
        // using the nested `supported` boolean field of each capability object
        body["capabilities"]?.jsonObject?.let { capabilities ->
            for ((key, value) in capabilities) {
                val supported = (value as? JsonObject)?.get("supported")?.jsonPrimitive?.booleanOrNull
                if (supported != null) {
                    span.setAttribute("gen_ai.response.model.capabilities.$key", supported)
                }
            }
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use SSE streaming
    }

    /**
     * Detects which models operation is being called based on the URL path.
     *
     * URL patterns:
     * - `GET /v1/models`           → `"list"`
     * - `GET /v1/models/{id}`      → `"retrieve"`
     */
    private fun detectOperation(url: TracyHttpUrl): String {
        return if (modelIdFromUrl(url) != null) "retrieve" else "list"
    }

    /**
     * Extracts the model ID from the URL path if present (i.e., for retrieve operations).
     * Returns `null` for list operations where no model ID is in the path.
     */
    private fun modelIdFromUrl(url: TracyHttpUrl): String? {
        val segments = url.pathSegments
        val modelsIndex = segments.indexOf("models")
        if (modelsIndex == -1) {
            logger.warn { "No 'models' segment in URL path: ${segments.joinToString("/")}" }
            return null
        }
        return segments.drop(modelsIndex + 1).firstOrNull { it.isNotBlank() }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
