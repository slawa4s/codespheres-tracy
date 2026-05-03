/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.asJson
import org.jetbrains.ai.tracy.openai.adapters.handlers.OpenAIApiUtils

/**
 * Handler for the OpenAI Models API.
 *
 * The Models API provides endpoints for listing and retrieving available models:
 * 1. `GET /v1/models` - List available models (`models.list`)
 * 2. `GET /v1/models/{model_id}` - Retrieve a specific model (`models.retrieve`)
 *
 * This handler detects the specific route from the URL and sets
 * `gen_ai.operation.name` accordingly, overriding the bare `"list"` value
 * that `setCommonResponseAttributes` would otherwise read from the response
 * `"object"` field.
 *
 * See [Models API Reference](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setNetworkRequestAttributes(span, request)
        val operationName = deriveOperationName(request.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)
        span.setAttribute("openai.api.type", "models")
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        OpenAIApiUtils.setHttpStatusCode(span, response)
        // Override gen_ai.operation.name set by setCommonResponseAttributes from the
        // response "object" field (which yields bare "list") with the URL-derived value.
        val operationName = deriveOperationName(response.url)
        span.setAttribute(GEN_AI_OPERATION_NAME, operationName)

        val body = response.body.asJson()?.jsonObject ?: return
        body["id"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.model.id", it) }
        body["object"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.object", it) }
        body["created"]?.jsonPrimitive?.longOrNull?.let { span.setAttribute("tracy.response.created", it) }
        body["owned_by"]?.jsonPrimitive?.contentOrNull?.let { span.setAttribute("tracy.response.owned_by", it) }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use server-sent events streaming
        logger.warn { "Models API does not use server-sent events streaming" }
    }

    /**
     * Derives the `gen_ai.operation.name` from the request URL.
     *
     * - `GET /v1/models` → `"models.list"`
     * - `GET /v1/models/{model_id}` → `"models.retrieve"`
     */
    private fun deriveOperationName(url: TracyHttpUrl): String {
        val segments = url.pathSegments
        val modelsIndex = segments.indexOf("models")
        if (modelsIndex == -1) {
            logger.warn { "Failed to detect models route. Endpoint has no `models` path segment: ${segments.joinToString(separator = "/")}" }
            return "models.list"
        }

        val hasModelId = segments.size > modelsIndex + 1 &&
                segments[modelsIndex + 1].isNotBlank()

        return if (hasModelId) "models.retrieve" else "models.list"
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
