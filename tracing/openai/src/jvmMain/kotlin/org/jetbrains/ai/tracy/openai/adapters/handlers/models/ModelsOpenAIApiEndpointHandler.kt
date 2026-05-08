/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import io.opentelemetry.api.trace.Span
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.asJson

/**
 * Handler for OpenAI Models API endpoints.
 *
 * Handles two endpoints:
 * 1. `GET /v1/models` - List all available models (`models.list`)
 * 2. `GET /v1/models/{model_id}` - Retrieve a specific model (`models.retrieve`)
 *
 * This handler detects the specific route from the URL path segments, stores the
 * model ID in a [ThreadLocal] during the request phase for use in the response phase.
 *
 * See [Models API Reference](https://platform.openai.com/docs/api-reference/models)
 */
internal class ModelsOpenAIApiEndpointHandler : EndpointApiHandler {

    /**
     * Stores the model ID extracted from the URL during request handling so it can be
     * applied in [handleResponseAttributes] to set [GEN_AI_RESPONSE_MODEL].
     * Null for list requests.
     */
    private val modelIdThreadLocal = ThreadLocal<String>()

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        val segments = request.url.pathSegments
        val modelsIndex = segments.indexOf("models")

        val hasModelId = modelsIndex != -1 &&
                segments.size > modelsIndex + 1 &&
                segments[modelsIndex + 1].isNotBlank()

        if (hasModelId) {
            // Retrieve: GET /v1/models/{model_id}
            val modelId = segments[modelsIndex + 1]
            span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_RETRIEVE)
            span.setAttribute(GEN_AI_REQUEST_MODEL, modelId)
            modelIdThreadLocal.set(modelId)
        } else {
            // List: GET /v1/models
            span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_LIST)
        }
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        val modelId = modelIdThreadLocal.get()
        modelIdThreadLocal.remove()

        val body = response.body.asJson()?.jsonObject ?: return

        body["object"]?.jsonPrimitive?.content?.let {
            span.setAttribute("tracy.response.object", it)
        }

        if (modelId != null) {
            body["id"]?.jsonPrimitive?.content?.let {
                span.setAttribute("tracy.response.model.id", it)
            }
            span.setAttribute(GEN_AI_RESPONSE_MODEL, modelId)
        }
    }

    override fun handleStreaming(span: Span, events: String) {
        // Models API does not use SSE streaming
    }

    companion object {
        private const val OPERATION_RETRIEVE = "models.retrieve"
        private const val OPERATION_LIST = "models.list"
    }
}
