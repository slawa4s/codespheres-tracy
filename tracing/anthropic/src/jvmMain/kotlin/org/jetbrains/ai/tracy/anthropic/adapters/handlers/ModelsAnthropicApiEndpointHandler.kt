/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for Anthropic Models API.
 *
 * Maps HTTP method + URL path to `gen_ai.operation.name`:
 * - `GET /v1/models`              → `"list"`
 * - `GET /v1/models/{model_id}`   → `"retrieve"`
 *
 * For "retrieve" operations, the model alias is extracted from the URL path (e.g., `claude-haiku-4-5`)
 * and set as both `gen_ai.request.model` and `gen_ai.response.model`. This ensures that the
 * observed model attribute matches the alias the caller used, rather than the versioned id
 * returned by the API (e.g., `claude-haiku-4-5-20251001`), which is mapped to `gen_ai.response.id`.
 *
 * Additional response attributes for retrieve operations:
 * - `gen_ai.response.model.id`           → versioned id from response body
 * - `gen_ai.response.model.display_name` → display name
 * - `gen_ai.response.model.created_at`   → creation timestamp
 * - `gen_ai.response.model.max_input_tokens`  → maximum input tokens (Long)
 * - `gen_ai.response.model.max_output_tokens` → maximum output tokens (Long)
 * - `gen_ai.response.model.capabilities.{key}` → capability flags (boolean)
 *
 * See: [Models API](https://docs.anthropic.com/en/api/models-list)
 */
internal class ModelsAnthropicApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the model alias extracted from the request URL so it can be reused
     * in [handleResponseAttributes] when the response attributes are populated.
     *
     * A [ThreadLocal] is used to avoid shared mutable state between concurrent requests.
     */
    private val requestModelAlias = ThreadLocal<String?>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        span.setAttribute("anthropic.api.type", "models")

        val segments = request.url.pathSegments
        val modelsIndex = segments.indexOf("models")

        if (modelsIndex == -1) {
            logger.warn { "No 'models' segment in URL path: ${segments.joinToString("/")}" }
            span.setAttribute(GEN_AI_OPERATION_NAME, "list")
            requestModelAlias.set(null)
            return
        }

        val afterModels = segments.drop(modelsIndex + 1).filter { it.isNotBlank() }

        if (afterModels.isNotEmpty()) {
            val modelAlias = afterModels.first()
            span.setAttribute(GEN_AI_OPERATION_NAME, "retrieve")
            span.setAttribute(GEN_AI_REQUEST_MODEL, modelAlias)
            requestModelAlias.set(modelAlias)
        } else {
            span.setAttribute(GEN_AI_OPERATION_NAME, "list")
            requestModelAlias.set(null)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val modelAlias = requestModelAlias.get()
        requestModelAlias.remove()

        val body = response.body.asJson()?.jsonObject ?: return

        // Detect list response by presence of a "data" array (models.list endpoint)
        val dataArray = body["data"]
        if (dataArray is JsonArray) {
            span.setAttribute("gen_ai.response.list.count", dataArray.size.toLong())
            body["has_more"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.list.has_more", it)
            }
            body["first_id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.list.first_id", it)
            }
            body["last_id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("gen_ai.response.list.last_id", it)
            }
            return
        }

        // Versioned model id (e.g., "claude-haiku-4-5-20251001") → gen_ai.response.id + gen_ai.response.model.id
        body["id"]?.jsonPrimitive?.content?.let { id ->
            span.setAttribute(GEN_AI_RESPONSE_ID, id)
            span.setAttribute("gen_ai.response.model.id", id)
        }

        // Set gen_ai.response.model to the URL alias so it aligns with gen_ai.request.model
        // (the caller's alias, not the versioned id returned by the API)
        if (modelAlias != null) {
            span.setAttribute(GEN_AI_RESPONSE_MODEL, modelAlias)
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

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
